package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 待审核阶段的群身份与原管理员事实；不要求目标已经在群内。 */
@Service
public class JoinTaskApprovalFacts {
    private final AccountProtocolLookupService accounts;
    private final GroupExecutionAccountSelector selector;
    private final FixedAccountGroupMetadataPort metadata;

    /** 复用账号域身份、群域权限范围与定点元数据端口。 */
    public JoinTaskApprovalFacts(AccountProtocolLookupService accounts, GroupExecutionAccountSelector selector,
            FixedAccountGroupMetadataPort metadata) {
        this.accounts = accounts; this.selector = selector; this.metadata = metadata;
    }

    /** 按本群原有管理员选择操作账号，不使用待入群的新号。 */
    public void selectActor(JoinTaskApprovalWork work, String group, ProtocolAccountRef target) {
        var state = work.approval();
        var candidates = selector.findJoinTaskAdminCandidates(work.result().getTenantId(), group,
                work.result().getAccountId(), work.task().getOwnerUserId());
        if (candidates.isEmpty()) throw new IllegalArgumentException("未找到可用的原群管理员");
        ProtocolException lastFailure = null;
        for (var candidate : candidates.stream().limit(3).toList()) {
            try {
                var snapshot = snapshot(candidate.protocolRef(), group);
                var operator = member(snapshot, candidate.wsPhone());
                if (operator.isEmpty() && !snapshot.participantsComplete()) {
                    lastFailure = new ProtocolException(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED, "原管理员角色未确认");
                    continue;
                }
                if (operator.isPresent() && (Boolean.TRUE.equals(operator.get().admin())
                        || Boolean.TRUE.equals(operator.get().owner()))) {
                    state.setActorAccountId(candidate.accountId()); state.setActorPhone(candidate.wsPhone());
                    state.setTargetPhone(target.wsPhone());
                    return;
                }
            } catch (ProtocolException error) { lastFailure = error; }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalArgumentException("无管理员权限");
    }

    /** 当前目标账号必须与待审回执及已保存身份一致。 */
    public ProtocolAccountRef target(JoinTaskApprovalWork work) {
        var ref = accounts.findActiveProtocolRef(work.result().getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("进群账号不存在或身份失效"));
        var state = work.approval();
        if (!ref.protocolAccountId().equals(state.getTargetProtocolAccountId())
                || (!state.getTargetPhone().isBlank() && !ref.wsPhone().equals(state.getTargetPhone()))) {
            throw new IllegalArgumentException("进群账号身份已变化");
        }
        return ref;
    }

    /** 每次动作重新约束在线、归属与原号身份。 */
    public ProtocolAccountRef actor(JoinTaskApprovalWork work) {
        var state = work.approval();
        return selector.findJoinTaskAdminCandidates(work.result().getTenantId(), state.getGroupJid(),
                        work.result().getAccountId(), work.task().getOwnerUserId()).stream()
                .filter(a -> a.accountId().equals(state.getActorAccountId()) && a.wsPhone().equals(state.getActorPhone()))
                .map(a -> a.protocolRef()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("原管理员离线、权限失效或已不在可调用范围"));
    }

    /** 只接受本群的明确成员快照。 */
    public GroupMetadataResult snapshot(ProtocolAccountRef actor, String group) {
        var result = metadata.getMetadata(actor, group);
        if (result == null || !group.equals(result.groupJid())) {
            throw new ProtocolException(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED, "群成员结果未确认");
        }
        if (Boolean.TRUE.equals(result.stateAbnormal())) throw new IllegalArgumentException("群组状态异常");
        return result;
    }

    /** 同时支持 PN、已映射的 LID 和明确手机号。 */
    public static Optional<GroupParticipantResult> member(GroupMetadataResult snapshot, String phone) {
        String jid = WhatsappJids.userJid(phone);
        return (snapshot.participants() == null ? List.<GroupParticipantResult>of() : snapshot.participants()).stream()
                .filter(p -> jid.equals(p.jid()) || jid.equals(p.pnJid())
                        || (p.phone() != null && p.phone().matches("[+0-9]+")
                            && jid.equals(WhatsappJids.userJid(p.phone())))).findFirst();
    }

}
