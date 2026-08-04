package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskSupplementPullerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在联系人阶段前执行补充拉手的踩链接与实时在群复核。 */
@Component
public class PullTaskSupplementPullerProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskSupplementPullerProcessor.class);
    private static final String MEMBERSHIP_UNKNOWN = "PULLER_MEMBERSHIP_UNCONFIRMED";
    private static final String PENDING_APPROVAL = "PULLER_JOIN_PENDING_APPROVAL";
    private static final Set<ProtocolErrorCode> UNCERTAIN_ERRORS = EnumSet.of(
            ProtocolErrorCode.TIMEOUT, ProtocolErrorCode.NETWORK,
            ProtocolErrorCode.HTTP_ERROR, ProtocolErrorCode.ACCOUNT_BUSY,
            ProtocolErrorCode.WORKER_BUSY, ProtocolErrorCode.RATE_LIMITED,
            ProtocolErrorCode.TEMPORARY_FAILURE, ProtocolErrorCode.UNKNOWN);

    private final PullTaskSupplementPullerTransactionService transactions;
    private final GroupJoinPort joinPort;
    private final GroupMemberListPort memberListPort;

    public PullTaskSupplementPullerProcessor(
            PullTaskSupplementPullerTransactionService transactions,
            GroupJoinPort joinPort,
            GroupMemberListPort memberListPort) {
        this.transactions = transactions;
        this.joinPort = joinPort;
        this.memberListPort = memberListPort;
    }

    /** 若当前联系人检查点包含补充拉手踩链接指令则先处理它。 */
    public Optional<PullTaskExecutionDispatchResult> processIfPresent(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        PullTaskSupplementPullerPreparation preparation =
                transactions.prepare(candidate, lockOwner, now);
        if (!preparation.handled()) {
            return Optional.empty();
        }
        if (!preparation.ready()) {
            return Optional.of(preparation.result());
        }
        PullTaskSupplementPullerWork work = preparation.work();
        PullTaskSupplementPullerOutcome outcome = execute(work);
        return Optional.of(transactions.complete(work, outcome, now));
    }

    private PullTaskSupplementPullerOutcome execute(PullTaskSupplementPullerWork work) {
        try {
            CommandFact fact = work.verificationOnly()
                    ? CommandFact.unknown(MEMBERSHIP_UNKNOWN) : join(work);
            GroupParticipantResult member = findMember(
                    memberListPort.list(work.memberQuery()), work.target().wsPhone());
            if (member != null) {
                return PullTaskSupplementPullerOutcome.confirmed();
            }
            if (fact.failed()) {
                return PullTaskSupplementPullerOutcome.failed(fact.reasonCode());
            }
            String reasonCode = fact.reasonCode();
            if (reasonCode == null || reasonCode.isBlank()) {
                reasonCode = MEMBERSHIP_UNKNOWN;
            }
            return PullTaskSupplementPullerOutcome.unknown(reasonCode);
        } catch (RuntimeException exception) {
            log.warn("补充拉手踩链接异常 tenantId={} executionId={} roleRowId={} errorType={}",
                    work.tenantId(), work.executionId(), work.targetGroupAccountId(),
                    exception.getClass().getSimpleName());
            return uncertain(exception);
        }
    }

    private CommandFact join(PullTaskSupplementPullerWork work) {
        GroupJoinResult result = joinPort.join(work.joinCommand());
        if (result != null && result.joined()) {
            return CommandFact.success();
        }
        if (result != null && result.outcome() == GroupJoinOutcome.PENDING_APPROVAL) {
            return CommandFact.unknown(PENDING_APPROVAL);
        }
        return CommandFact.unknown(MEMBERSHIP_UNKNOWN);
    }

    private static PullTaskSupplementPullerOutcome uncertain(RuntimeException exception) {
        String reason = exception instanceof ProtocolException protocol
                ? protocol.errorCode().name() : ProtocolErrorCode.UNKNOWN.name();
        boolean stable = exception instanceof ProtocolException protocol
                && !protocol.retryable().orElse(false)
                && !UNCERTAIN_ERRORS.contains(protocol.errorCode());
        return stable ? PullTaskSupplementPullerOutcome.failed(reason)
                : PullTaskSupplementPullerOutcome.unknown(reason);
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

    private record CommandFact(boolean failed, String reasonCode) {
        private static CommandFact success() {
            return new CommandFact(false, null);
        }

        private static CommandFact unknown(String reasonCode) {
            return new CommandFact(false, reasonCode);
        }
    }
}
