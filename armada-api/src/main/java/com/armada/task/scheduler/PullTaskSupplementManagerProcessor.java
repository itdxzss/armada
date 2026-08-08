package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.task.model.dto.PullTaskSupplementManagerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在事务外执行补充管理员的进群、在群复核、提权和权限复核。 */
@Component
public class PullTaskSupplementManagerProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskSupplementManagerProcessor.class);
    private static final String ENTRY_UNKNOWN = "MANAGER_MEMBERSHIP_UNCONFIRMED";
    private static final String ADMIN_UNKNOWN = "MANAGER_ADMIN_PERMISSION_UNCONFIRMED";
    private static final Set<String> SUCCESS_CODES = Set.of(
            "OK", "200", "SUCCESS", "ALREADY_IN", "ALREADY_ADMIN");
    private static final Set<ProtocolErrorCode> UNCERTAIN_ERRORS = EnumSet.of(
            ProtocolErrorCode.TIMEOUT, ProtocolErrorCode.NETWORK,
            ProtocolErrorCode.HTTP_ERROR, ProtocolErrorCode.ACCOUNT_BUSY,
            ProtocolErrorCode.WORKER_BUSY, ProtocolErrorCode.RATE_LIMITED,
            ProtocolErrorCode.TEMPORARY_FAILURE, ProtocolErrorCode.UNKNOWN);

    private final PullTaskSupplementManagerTransactionService transactions;
    private final GroupJoinPort joinPort;
    private final GroupParticipantPort participantPort;
    private final GroupMemberListPort memberListPort;

    /**
     * @param transactions 补充管理员短事务
     * @param joinPort 踩链接端口
     * @param participantPort 邀请与提权端口
     * @param memberListPort 实时成员与权限事实端口
     */
    public PullTaskSupplementManagerProcessor(
            PullTaskSupplementManagerTransactionService transactions,
            GroupJoinPort joinPort,
            GroupParticipantPort participantPort,
            GroupMemberListPort memberListPort) {
        this.transactions = transactions;
        this.joinPort = joinPort;
        this.participantPort = participantPort;
        this.memberListPort = memberListPort;
    }

    /** 若当前管理员检查点属于人工补充则处理，否则返回空让原链路继续。 */
    public Optional<PullTaskExecutionDispatchResult> processIfPresent(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        PullTaskSupplementManagerPreparation preparation =
                transactions.prepare(candidate, lockOwner, now);
        if (!preparation.handled()) {
            return Optional.empty();
        }
        if (!preparation.ready()) {
            return Optional.of(preparation.result());
        }
        PullTaskSupplementManagerWork work = preparation.work();
        PullTaskSupplementManagerOutcome outcome = execute(work);
        return Optional.of(transactions.complete(work, outcome, now));
    }

    private PullTaskSupplementManagerOutcome execute(PullTaskSupplementManagerWork work) {
        try {
            return work.operation() == PullTaskSupplementManagerOperation.PROMOTE_ADMIN
                    ? promote(work) : enter(work);
        } catch (RuntimeException exception) {
            log.warn("补充管理员协议异常 tenantId={} executionId={} roleRowId={} operation={} errorType={}",
                    work.tenantId(), work.executionId(), work.targetGroupAccountId(),
                    work.operation(), exception.getClass().getSimpleName());
            return uncertain(exception, work.operation());
        }
    }

    private PullTaskSupplementManagerOutcome enter(PullTaskSupplementManagerWork work) {
        if (work.verificationOnly()) {
            return verifyMembership(work, CommandFact.unknown(ENTRY_UNKNOWN));
        }
        if (work.operation() == PullTaskSupplementManagerOperation.JOIN_BY_LINK) {
            GroupJoinResult result = joinPort.join(work.joinCommand());
            if (result != null && result.joined()) {
                return PullTaskSupplementManagerOutcome.entryConfirmed();
            }
            if (result != null && result.outcome() == GroupJoinOutcome.PENDING_APPROVAL) {
                return PullTaskSupplementManagerOutcome.entryPendingApproval();
            }
            return verifyMembership(work, CommandFact.unknown(ENTRY_UNKNOWN));
        }
        CommandFact fact = invite(work);
        return verifyMembership(work, fact);
    }

    private CommandFact invite(PullTaskSupplementManagerWork work) {
        GroupParticipantBatchResult result = participantPort.updateParticipants(
                work.actor(), work.groupJid(), List.of(work.targetJid()),
                GroupParticipantAction.ADD);
        return commandFact(result, work.targetJid(), "MANAGER_INVITE_FAILED");
    }

    private PullTaskSupplementManagerOutcome verifyMembership(
            PullTaskSupplementManagerWork work, CommandFact fact) {
        GroupParticipantResult target = findMember(
                memberListPort.list(work.targetMemberQuery()), work.target().wsPhone());
        if (target != null) {
            return PullTaskSupplementManagerOutcome.entryConfirmed();
        }
        if (fact.kind() == CommandKind.FAILED) {
            return PullTaskSupplementManagerOutcome.entryFailed(fact.reasonCode());
        }
        return PullTaskSupplementManagerOutcome.entryUnknown(
                hasText(fact.reasonCode()) ? fact.reasonCode() : ENTRY_UNKNOWN);
    }

    private PullTaskSupplementManagerOutcome promote(PullTaskSupplementManagerWork work) {
        GroupParticipantResult target = findMember(
                memberListPort.list(work.targetMemberQuery()), work.target().wsPhone());
        if (hasAdminPermission(target)) {
            return PullTaskSupplementManagerOutcome.adminConfirmed();
        }
        if (work.verificationOnly()) {
            return PullTaskSupplementManagerOutcome.adminUnknown(ADMIN_UNKNOWN);
        }
        GroupParticipantResult actor = findMember(
                memberListPort.list(work.actorPermissionQuery()), work.actor().wsPhone());
        if (!hasAdminPermission(actor)) {
            return PullTaskSupplementManagerOutcome.adminFailed(
                    ProtocolErrorCode.GROUP_PERMISSION_DENIED.name());
        }
        CommandFact fact = commandFact(participantPort.updateParticipants(
                work.actor(), work.groupJid(), List.of(work.targetJid()),
                GroupParticipantAction.PROMOTE), work.targetJid(), "MANAGER_PROMOTE_FAILED");
        return verifyAdminAfterCommand(work, fact);
    }

    private PullTaskSupplementManagerOutcome verifyAdminAfterCommand(
            PullTaskSupplementManagerWork work, CommandFact fact) {
        GroupParticipantResult target = findMember(
                memberListPort.list(work.targetMemberQuery()), work.target().wsPhone());
        if (hasAdminPermission(target)) {
            return PullTaskSupplementManagerOutcome.adminConfirmed();
        }
        if (fact.kind() == CommandKind.FAILED) {
            return PullTaskSupplementManagerOutcome.adminFailed(fact.reasonCode());
        }
        return PullTaskSupplementManagerOutcome.adminUnknown(ADMIN_UNKNOWN);
    }

    private static PullTaskSupplementManagerOutcome uncertain(
            RuntimeException exception,
            PullTaskSupplementManagerOperation operation) {
        String reason = exception instanceof ProtocolException protocol
                ? protocol.errorCode().name() : ProtocolErrorCode.UNKNOWN.name();
        boolean stable = exception instanceof ProtocolException protocol
                && !protocol.retryable().orElse(false)
                && !UNCERTAIN_ERRORS.contains(protocol.errorCode());
        if (operation == PullTaskSupplementManagerOperation.PROMOTE_ADMIN) {
            return stable ? PullTaskSupplementManagerOutcome.adminFailed(reason)
                    : PullTaskSupplementManagerOutcome.adminUnknown(reason);
        }
        return stable ? PullTaskSupplementManagerOutcome.entryFailed(reason)
                : PullTaskSupplementManagerOutcome.entryUnknown(reason);
    }

    private static CommandFact commandFact(
            GroupParticipantBatchResult result,
            String targetJid,
            String fallbackFailure) {
        if (result == null || result.results() == null) {
            return CommandFact.unknown(ProtocolErrorCode.UNKNOWN.name());
        }
        for (GroupParticipantBatchResult.Item item : result.results()) {
            if (item == null || !sameIdentity(targetJid, item.jid())) {
                continue;
            }
            if (successCode(item.status()) || successCode(item.rawStatus())) {
                return CommandFact.success();
            }
            if (hasText(item.rawStatus()) || hasText(item.status())) {
                return CommandFact.failed(hasText(item.rawStatus())
                        ? item.rawStatus() : fallbackFailure);
            }
        }
        return CommandFact.unknown(ProtocolErrorCode.UNKNOWN.name());
    }

    private static GroupParticipantResult findMember(
            List<GroupParticipantResult> members, String identity) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        String expected = phone(identity);
        return members.stream().filter(java.util.Objects::nonNull)
                .filter(member -> expected.equals(phone(
                        member.phone() == null ? member.jid() : member.phone())))
                .findFirst().orElse(null);
    }

    private static boolean hasAdminPermission(GroupParticipantResult member) {
        return member != null
                && (Boolean.TRUE.equals(member.admin()) || Boolean.TRUE.equals(member.owner()));
    }

    private static boolean sameIdentity(String first, String second) {
        return phone(first).equals(phone(second));
    }

    private static String phone(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        normalized = at < 0 ? normalized : normalized.substring(0, at);
        int device = normalized.indexOf(':');
        normalized = device < 0 ? normalized : normalized.substring(0, device);
        return normalized.replaceAll("[^0-9]", "");
    }

    private static boolean successCode(String value) {
        return hasText(value)
                && SUCCESS_CODES.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum CommandKind { SUCCESS, FAILED, UNKNOWN }

    private record CommandFact(CommandKind kind, String reasonCode) {
        private static CommandFact success() {
            return new CommandFact(CommandKind.SUCCESS, null);
        }
        private static CommandFact failed(String reasonCode) {
            return new CommandFact(CommandKind.FAILED, reasonCode);
        }
        private static CommandFact unknown(String reasonCode) {
            return new CommandFact(CommandKind.UNKNOWN, reasonCode);
        }
    }
}
