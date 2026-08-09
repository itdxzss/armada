package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskSupplementPullerWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.EnumSet;
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
    private final PullTaskMemberQueryAwaitService memberQueryAwaitService;

    public PullTaskSupplementPullerProcessor(
            PullTaskSupplementPullerTransactionService transactions,
            GroupJoinPort joinPort,
            PullTaskMemberQueryAwaitService memberQueryAwaitService) {
        this.transactions = transactions;
        this.joinPort = joinPort;
        this.memberQueryAwaitService = memberQueryAwaitService;
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
        PullTaskSupplementPullerOutcome outcome = execute(work, candidate.getTaskId(), now);
        if (outcome == null) {
            return Optional.of(PullTaskExecutionDispatchResult.DEFERRED);
        }
        return Optional.of(transactions.complete(work, outcome, now));
    }

    private PullTaskSupplementPullerOutcome execute(
            PullTaskSupplementPullerWork work, long taskId, long now) {
        try {
            CommandFact fact = work.verificationOnly()
                    ? CommandFact.unknown(MEMBERSHIP_UNKNOWN) : join(work);
            String targetJid = WhatsappJids.userJid(work.target().wsPhone());
            PullTaskMemberQueryResult query = memberQueryAwaitService.readOrDefer(
                    work.tenantId(), new PullTaskMemberQueryRequest(
                            taskId, work.executionId(),
                            "supplement-puller-membership:" + work.actionId(),
                            PullTaskMemberQueryPurpose.SUPPLEMENT_PULLER_MEMBERSHIP,
                            work.target(), work.groupJid(), java.util.List.of(targetJid)),
                    work.expectedVersion(), work.lockOwner(),
                    PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(), now);
            if (query.state() == PullTaskMemberQueryResult.State.PENDING) {
                return null;
            }
            if (query.state() == PullTaskMemberQueryResult.State.AVAILABLE
                    && query.members().stream().anyMatch(member -> member.inGroup()
                    && targetJid.equals(member.targetJid()))) {
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

    private record CommandFact(boolean failed, String reasonCode) {
        private static CommandFact success() {
            return new CommandFact(false, null);
        }

        private static CommandFact unknown(String reasonCode) {
            return new CommandFact(false, reasonCode);
        }
    }
}
