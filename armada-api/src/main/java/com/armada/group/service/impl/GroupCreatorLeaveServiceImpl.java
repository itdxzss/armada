package com.armada.group.service.impl;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.enums.GroupCreatorLeaveStatus;
import com.armada.group.model.vo.GroupCreatorLeaveAccount;
import com.armada.group.model.vo.GroupCreatorLeaveCapabilityVO;
import com.armada.group.model.vo.GroupCreatorLeavePlan;
import com.armada.group.model.vo.GroupCreatorLeaveResultVO;
import com.armada.group.service.GroupCreatorLeaveService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 群主退群默认编排；准入事实全部来自 WhatsApp 事件维护的本地投影。 */
@Service
public class GroupCreatorLeaveServiceImpl implements GroupCreatorLeaveService {

    private static final Logger log = LoggerFactory.getLogger(GroupCreatorLeaveServiceImpl.class);

    private static final int ROLE_MEMBER = 1;
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_OWNER = 3;
    private static final String PARTICIPANT_OK = "OK";

    private static final Comparator<GroupCreatorLeaveAccount> CANDIDATE_ORDER =
            Comparator.comparingInt(GroupCreatorLeaveServiceImpl::offlineOrder)
                    .thenComparing(
                            GroupCreatorLeaveAccount::membershipActiveSinceAt,
                            Comparator.nullsLast(Long::compareTo))
                    .thenComparing(GroupCreatorLeaveAccount::accountId);

    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupParticipantPort participantPort;
    private final GroupLeavePort leavePort;

    public GroupCreatorLeaveServiceImpl(
            AccountGroupMembershipMapper membershipMapper,
            GroupParticipantPort participantPort,
            GroupLeavePort leavePort) {
        this.membershipMapper = membershipMapper;
        this.participantPort = participantPort;
        this.leavePort = leavePort;
    }

    @Override
    public GroupCreatorLeaveCapabilityVO capability(Long groupLinkId) {
        GroupCreatorLeavePlan plan = plan(groupLinkId, null);
        if (!plan.executable()) {
            return new GroupCreatorLeaveCapabilityVO(
                    false,
                    plan.failure().name(),
                    message(plan.failure()));
        }
        return new GroupCreatorLeaveCapabilityVO(true, null, null);
    }

    @Override
    public GroupCreatorLeavePlan plan(Long groupLinkId, Long preferredCreatorAccountId) {
        return select(groupLinkId, preferredCreatorAccountId);
    }

    @Override
    public GroupCreatorLeaveResultVO execute(Long groupLinkId, Long preferredCreatorAccountId) {
        GroupCreatorLeavePlan plan = plan(groupLinkId, preferredCreatorAccountId);
        if (!plan.executable()) {
            return result(plan.failure());
        }

        GroupCreatorLeaveAccount owner = plan.owner();
        GroupCreatorLeaveAccount memberToPromote = plan.memberToPromote();
        if (memberToPromote != null && !promote(owner, memberToPromote)) {
            return result(GroupCreatorLeaveStatus.PROMOTION_FAILED);
        }

        try {
            leavePort.leave(owner.protocolRef(), owner.groupJid());
            return result(GroupCreatorLeaveStatus.SUCCESS);
        } catch (ProtocolException ex) {
            log.warn("群主退群协议调用失败: groupLinkId={}, errorCode={}",
                    groupLinkId, ex.errorCode());
            return result(GroupCreatorLeaveStatus.LEAVE_FAILED);
        }
    }

    private GroupCreatorLeavePlan select(Long groupLinkId, Long preferredCreatorAccountId) {
        List<GroupCreatorLeaveAccount> accounts = membershipMapper.selectCreatorLeaveAccounts(groupLinkId);
        List<GroupCreatorLeaveAccount> safeAccounts = accounts == null ? List.of() : accounts;

        GroupCreatorLeaveAccount owner = safeAccounts.stream()
                .filter(account -> account.role() == ROLE_OWNER)
                .filter(account -> preferredCreatorAccountId == null
                        || Objects.equals(account.accountId(), preferredCreatorAccountId))
                .findFirst()
                .orElse(null);
        if (owner == null) {
            return GroupCreatorLeavePlan.failed(GroupCreatorLeaveStatus.NOT_CREATOR);
        }
        if (!isOperationalOwner(owner)) {
            return GroupCreatorLeavePlan.failed(GroupCreatorLeaveStatus.CREATOR_UNAVAILABLE);
        }

        boolean controlledAdminExists = safeAccounts.stream()
                .filter(account -> !Objects.equals(account.accountId(), owner.accountId()))
                .anyMatch(GroupCreatorLeaveServiceImpl::isAvailableAdmin);
        if (controlledAdminExists) {
            return new GroupCreatorLeavePlan(owner, null, null);
        }

        GroupCreatorLeaveAccount memberToPromote = safeAccounts.stream()
                .filter(account -> !Objects.equals(account.accountId(), owner.accountId()))
                .filter(GroupCreatorLeaveServiceImpl::isAvailableMember)
                .sorted(CANDIDATE_ORDER)
                .findFirst()
                .orElse(null);
        if (memberToPromote == null) {
            return GroupCreatorLeavePlan.failed(GroupCreatorLeaveStatus.NO_AVAILABLE_CONTROLLER);
        }
        return new GroupCreatorLeavePlan(owner, memberToPromote, null);
    }

    private boolean promote(
            GroupCreatorLeaveAccount owner,
            GroupCreatorLeaveAccount candidate) {
        try {
            GroupParticipantBatchResult response = participantPort.updateParticipants(
                    owner.protocolRef(),
                    owner.groupJid(),
                    List.of(candidate.participantJid()),
                    GroupParticipantAction.PROMOTE);
            return response != null
                    && !response.partial()
                    && response.results() != null
                    && response.results().stream().anyMatch(item -> item != null
                            && sameJid(candidate.participantJid(), item.jid())
                            && PARTICIPANT_OK.equalsIgnoreCase(item.status()));
        } catch (ProtocolException ex) {
            log.warn("群主退群权限转移失败: groupJid={}, errorCode={}",
                    owner.groupJid(), ex.errorCode());
            return false;
        }
    }

    private static boolean isOperationalOwner(GroupCreatorLeaveAccount account) {
        return Objects.equals(account.loginState(), AccountLoginStateCode.ONLINE)
                && Objects.equals(account.accountState(), AccountStateCode.NORMAL);
    }

    private static boolean isAvailableAdmin(GroupCreatorLeaveAccount account) {
        return account.role() == ROLE_ADMIN
                && isNormalControlledParticipant(account);
    }

    private static boolean isAvailableMember(GroupCreatorLeaveAccount account) {
        return account.role() == ROLE_MEMBER
                && isNormalControlledParticipant(account);
    }

    private static boolean isNormalControlledParticipant(GroupCreatorLeaveAccount account) {
        return account != null
                && Objects.equals(account.accountState(), AccountStateCode.NORMAL)
                && account.participantJid() != null
                && !account.participantJid().isBlank();
    }

    private static int offlineOrder(GroupCreatorLeaveAccount account) {
        return Objects.equals(account.loginState(), AccountLoginStateCode.ONLINE) ? 0 : 1;
    }

    private static boolean sameJid(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static GroupCreatorLeaveResultVO result(GroupCreatorLeaveStatus status) {
        return new GroupCreatorLeaveResultVO(status, message(status));
    }

    private static String message(GroupCreatorLeaveStatus status) {
        return switch (status) {
            case SUCCESS -> "群主退群成功";
            case NOT_CREATOR -> "当前账号不是建群者，无法执行群主退群";
            case CREATOR_UNAVAILABLE -> "建群者账号当前不可执行退群";
            case NO_AVAILABLE_CONTROLLER -> "当前群内无控端管理员或可提升的普通控端成员，无法执行群主退群";
            case PROMOTION_FAILED -> "控端成员管理员设置失败，未执行群主退群";
            case LEAVE_FAILED -> "建群者退群失败";
        };
    }

}
