package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在事务外执行管理员踩链接，并以实时成员列表确认在群。 */
@Component
public class PullTaskManagerJoinProcessor {

    private static final Logger log = LoggerFactory.getLogger(PullTaskManagerJoinProcessor.class);

    private final PullTaskManagerJoinTransactionService transactions;
    private final PullTaskSupplementManagerProcessor supplementProcessor;
    private final GroupJoinPort joinPort;
    private final GroupMemberListPort memberListPort;

    /**
     * @param transactions   管理员入群短事务
     * @param supplementProcessor 人工补充管理员处理器
     * @param joinPort       统一进群协议端口
     * @param memberListPort 实时群成员查询端口
     */
    public PullTaskManagerJoinProcessor(
            PullTaskManagerJoinTransactionService transactions,
            PullTaskSupplementManagerProcessor supplementProcessor,
            GroupJoinPort joinPort,
            GroupMemberListPort memberListPort) {
        this.transactions = transactions;
        this.supplementProcessor = supplementProcessor;
        this.joinPort = joinPort;
        this.memberListPort = memberListPort;
    }

    /** 执行一条处于 MANAGER_JOIN 阶段的执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        java.util.Optional<PullTaskExecutionDispatchResult> supplement =
                supplementProcessor.processIfPresent(candidate, lockOwner, now);
        if (supplement.isPresent()) {
            return supplement.get();
        }
        PullTaskManagerJoinPreparation preparation =
                transactions.prepare(candidate, lockOwner, now);
        if (!preparation.ready()) {
            return preparation.result();
        }
        PullTaskManagerJoinWork work = preparation.work();
        PullTaskManagerJoinOutcome outcome;
        try {
            outcome = joinAndVerify(work);
        } catch (RuntimeException ex) {
            outcome = exceptionOutcome(ex);
            log.warn("管理员踩链接或在群复核异常 tenantId={} executionId={} accountId={} errorType={}",
                    work.tenantId(), work.executionId(), work.payload().account().armadaAccountId(),
                    ex.getClass().getSimpleName());
        }
        return transactions.complete(work, outcome, now);
    }

    private PullTaskManagerJoinOutcome joinAndVerify(PullTaskManagerJoinWork work) {
        if (work.payload().knownGroupJid() != null
                && !work.payload().knownGroupJid().isBlank()) {
            return verifyMembership(work, work.payload().knownGroupJid());
        }
        GroupJoinResult result = joinPort.join(work.joinCommand());
        if (result == null || !result.joined() || result.groupJid().isBlank()) {
            String reason = result != null && result.outcome() != null
                    ? PullTaskExecutionReasonCode.MANAGER_JOIN_PENDING_APPROVAL.name()
                    : PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name();
            return PullTaskManagerJoinOutcome.unconfirmed(
                    result == null ? null : result.groupJid(), reason);
        }
        return verifyMembership(work, result.groupJid());
    }

    private PullTaskManagerJoinOutcome verifyMembership(
            PullTaskManagerJoinWork work, String groupJid) {
        List<GroupParticipantResult> members;
        try {
            members = memberListPort.list(work.memberListQuery(groupJid));
        } catch (RuntimeException ex) {
            log.warn("管理员实时在群复核异常 tenantId={} executionId={} accountId={} errorType={}",
                    work.tenantId(), work.executionId(), work.payload().account().armadaAccountId(),
                    ex.getClass().getSimpleName());
            return PullTaskManagerJoinOutcome.unconfirmed(
                    groupJid,
                    PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name());
        }
        if (containsAccount(members, work.payload().account().wsPhone())) {
            return PullTaskManagerJoinOutcome.confirmed(groupJid);
        }
        return PullTaskManagerJoinOutcome.unconfirmed(
                groupJid,
                PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name());
    }

    private static PullTaskManagerJoinOutcome exceptionOutcome(RuntimeException exception) {
        if (exception instanceof ProtocolException protocol) {
            ProtocolErrorCode code = protocol.errorCode();
            if (code == ProtocolErrorCode.INVITE_INVALID
                    || code == ProtocolErrorCode.INVITE_REVOKED
                    || code == ProtocolErrorCode.INVALID_GROUP_LINK
                    || code == ProtocolErrorCode.GROUP_UNAVAILABLE) {
                return PullTaskManagerJoinOutcome.executionFailed(code.name());
            }
            if (code == ProtocolErrorCode.ACCOUNT_NOT_FOUND
                    || code == ProtocolErrorCode.ACCOUNT_NOT_ONLINE
                    || code == ProtocolErrorCode.NEED_REAUTH
                    || code == ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED
                    || code == ProtocolErrorCode.GROUP_JOIN_REJECTED) {
                return PullTaskManagerJoinOutcome.managerFailed(code.name());
            }
        }
        return PullTaskManagerJoinOutcome.unconfirmed(
                null, PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name());
    }

    private static boolean containsAccount(
            List<GroupParticipantResult> members, String accountPhone) {
        if (members == null || members.isEmpty()) {
            return false;
        }
        String expected = phone(accountPhone);
        return members.stream()
                .filter(member -> member != null)
                .map(member -> member.phone() == null ? member.jid() : member.phone())
                .map(PullTaskManagerJoinProcessor::phone)
                .anyMatch(expected::equals);
    }

    private static String phone(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.replaceAll("[^0-9]", "");
    }
}
