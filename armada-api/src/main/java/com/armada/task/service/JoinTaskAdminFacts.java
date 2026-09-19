package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.JoinTaskAdminObservation;
import com.armada.task.model.dto.JoinTaskAdminWork;
import com.armada.task.model.entity.JoinTaskResult;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static com.armada.task.model.dto.JoinTaskAdminObservation.Kind.SUCCESS;
import static com.armada.task.model.dto.JoinTaskAdminObservation.Kind.READY;
import static com.armada.task.model.dto.JoinTaskAdminObservation.Kind.WAIT;
import static com.armada.task.model.dto.JoinTaskAdminObservation.Kind.FAILED;

/** 提权前核实本群原有管理员和目标成员，群事实仍由群域负责。 */
@Service
public class JoinTaskAdminFacts {
    private static final Logger log = LoggerFactory.getLogger(JoinTaskAdminFacts.class);
    private final AccountProtocolLookupService accounts;
    private final GroupExecutionAccountSelector selector;
    private final FixedAccountGroupMetadataPort metadata;
    private final GroupParticipantObservationService observations;

    /** 创建账号身份、当前角色查询与事实写入边界。 */
    public JoinTaskAdminFacts(AccountProtocolLookupService accounts, GroupExecutionAccountSelector selector,
            FixedAccountGroupMetadataPort metadata, GroupParticipantObservationService observations) {
        this.accounts = accounts;
        this.selector = selector;
        this.metadata = metadata;
        this.observations = observations;
    }

    /** 目标身份来自账号域，不能信任创建请求中的显示号码。 */
    public String targetJid(JoinTaskResult row) {
        return accounts.findActiveProtocolRef(row.getAccountId())
                .map(ref -> WhatsappJids.userJid(ref.wsPhone())).orElse("");
    }

    /** 当前尝试的执行者仍须在线且具备执行账号资格。 */
    public Optional<ProtocolAccountRef> actor(Long id) {
        return id == null ? Optional.empty() : accounts.findEligibleManagerProtocolRefs(List.of(id)).stream().findFirst();
    }

    /** 外部查询仅在无数据库行锁的调用路径执行。 */
    public JoinTaskAdminObservation inspect(JoinTaskAdminWork work) {
        JoinTaskResult row = work.row();
        if (row.getGroupJid() == null || !row.getGroupJid().endsWith("@g.us")) {
            return new JoinTaskAdminObservation(FAILED, null, "目标群身份未确认，无法设置管理员");
        }
        String target = targetJid(row);
        if (target.isBlank()) return new JoinTaskAdminObservation(FAILED, null, "进群账号不存在或协议身份无效");
        List<GroupExecutionAccount> candidates = selector.findJoinTaskAdminCandidates(
                row.getTenantId(), row.getGroupJid(), row.getAccountId(), work.task().getOwnerUserId());
        for (GroupExecutionAccount candidate : candidates.stream().limit(3).toList()) {
            Optional<GroupMetadataResult> snapshot = read(candidate.protocolRef(), row.getGroupJid());
            if (snapshot.isEmpty()) continue;
            Optional<GroupParticipantResult> member = member(snapshot.get(), target);
            if (member.isPresent() && admin(member.get())) return new JoinTaskAdminObservation(SUCCESS, null, "");
            if (member.isEmpty()) {
                if (snapshot.get().participantsComplete()) return new JoinTaskAdminObservation(FAILED, null, "目标账号已不在群内");
                continue;
            }
            Optional<GroupParticipantResult> operator = member(snapshot.get(), WhatsappJids.userJid(candidate.wsPhone()));
            if (operator.isPresent() && admin(operator.get())) {
                return new JoinTaskAdminObservation(READY, candidate.protocolRef(), "");
            }
        }
        // 目标账号可以读取角色以收敛“已经是管理员”，但不能给自己发提权命令。
        for (ProtocolAccountRef targetAccount : accounts.findOnlineProtocolRefs(List.of(row.getAccountId()))) {
            Optional<GroupMetadataResult> snapshot = read(targetAccount, row.getGroupJid());
            if (snapshot.flatMap(value -> member(value, target)).filter(JoinTaskAdminFacts::admin).isPresent()) {
                return new JoinTaskAdminObservation(SUCCESS, null, "");
            }
        }
        return new JoinTaskAdminObservation(WAIT, null, "等待本群原有的可用管理员或角色确认");
    }

    /** 写入带事件时间的管理员事实；更早的事件不能覆盖后续降权或退群。 */
    public void recordSuccess(JoinTaskResult row, long occurredAt, String eventId) {
        String target = targetJid(row);
        if (target.isBlank()) throw new IllegalStateException("目标账号身份丢失");
        observations.apply(List.of(new GroupParticipantObservation(
                row.getTenantId(), row.getAccountId(), row.getGroupJid(), target, target,
                target.substring(0, target.indexOf('@')), true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, occurredAt, eventId)));
    }

    private Optional<GroupMetadataResult> read(ProtocolAccountRef ref, String groupJid) {
        try {
            GroupMetadataResult result = metadata.getMetadata(ref, groupJid);
            return result != null && groupJid.equals(result.groupJid()) && !Boolean.TRUE.equals(result.stateAbnormal())
                    ? Optional.of(result) : Optional.empty();
        } catch (RuntimeException ex) {
            log.debug("进群管理员角色查询未确认 accountId={} errorType={}", ref.armadaAccountId(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<GroupParticipantResult> member(GroupMetadataResult snapshot, String target) {
        return (snapshot.participants() == null ? List.<GroupParticipantResult>of() : snapshot.participants()).stream()
                .filter(p -> target.equals(p.jid()) || target.equals(p.pnJid())
                        || (p.phone() != null && p.phone().matches("[+0-9]+") && target.equals(WhatsappJids.userJid(p.phone())))).findFirst();
    }

    private static boolean admin(GroupParticipantResult participant) {
        return Boolean.TRUE.equals(participant.admin()) || Boolean.TRUE.equals(participant.owner());
    }
}
