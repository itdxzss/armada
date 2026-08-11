package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在事务外执行管理员踩链接；仅在重启恢复已有群 JID 时实时复核成员关系。 */
@Component
public class PullTaskManagerJoinProcessor {

    private static final Logger log = LoggerFactory.getLogger(PullTaskManagerJoinProcessor.class);

    private final PullTaskExecutionTransactionService executionTransactions;
    private final PullTaskManagerJoinTransactionService transactions;
    private final PullTaskSupplementManagerProcessor supplementProcessor;
    private final PullTaskManagerJoinProtocolExecutor protocolExecutor;
    private final PullTaskMemberQueryAwaitService memberQueryAwaitService;

    /**
     * @param executionTransactions 待启动执行行并发槽位事务
     * @param transactions   管理员入群短事务
     * @param supplementProcessor 人工补充管理员处理器
     * @param protocolExecutor 管理员进群与链接恢复执行器
     * @param memberQueryAwaitService 异步群成员查询等待服务
     */
    public PullTaskManagerJoinProcessor(
            PullTaskExecutionTransactionService executionTransactions,
            PullTaskManagerJoinTransactionService transactions,
            PullTaskSupplementManagerProcessor supplementProcessor,
            PullTaskManagerJoinProtocolExecutor protocolExecutor,
            PullTaskMemberQueryAwaitService memberQueryAwaitService) {
        this.executionTransactions = executionTransactions;
        this.transactions = transactions;
        this.supplementProcessor = supplementProcessor;
        this.protocolExecutor = protocolExecutor;
        this.memberQueryAwaitService = memberQueryAwaitService;
    }

    /** 执行一条处于 MANAGER_JOIN 阶段的执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!startIfNeeded(candidate, lockOwner, now)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Optional<PullTaskExecutionDispatchResult> supplement =
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
            outcome = joinOrVerifyRecovery(work, candidate, now);
            if (outcome == null) {
                return PullTaskExecutionDispatchResult.DEFERRED;
            }
        } catch (RuntimeException ex) {
            outcome = exceptionOutcome(ex);
            if (ex instanceof ProtocolException protocol) {
                log.warn("管理员踩链接或在群复核异常 tenantId={} executionId={} accountId={} "
                                + "errorType={} errorCode={} protocolCode={} backend={} operation={} "
                                + "operationId={} groupJid={} retryable={}",
                        work.tenantId(), work.executionId(),
                        work.payload().account().armadaAccountId(), ex.getClass().getSimpleName(),
                        protocol.errorCode(), protocol.protocolCode().orElse(null),
                        protocol.backend().map(Enum::name).orElse(null),
                        protocol.operation().orElse(null), protocol.operationId().orElse(null),
                        work.payload().knownGroupJid(), protocol.retryable().orElse(null));
            } else {
                log.warn("管理员踩链接或在群复核异常 tenantId={} executionId={} accountId={} "
                                + "errorType={} groupJid={}",
                        work.tenantId(), work.executionId(),
                        work.payload().account().armadaAccountId(),
                        ex.getClass().getSimpleName(), work.payload().knownGroupJid());
            }
        }
        return transactions.complete(work, outcome, now);
    }

    private boolean startIfNeeded(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (candidate.getExecutionStatus() != PullTaskExecutionStatus.WAIT_START.code()) {
            return true;
        }
        Optional<PullTaskExecutionWork> started =
                executionTransactions.prepare(candidate, lockOwner, now);
        if (started.isEmpty()) {
            return false;
        }
        candidate.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        candidate.setVersion(started.get().expectedVersion());
        return true;
    }

    private PullTaskManagerJoinOutcome joinOrVerifyRecovery(
            PullTaskManagerJoinWork work, PullTaskGroupExecution candidate, long now) {
        if (work.payload().knownGroupJid() != null
                && !work.payload().knownGroupJid().isBlank()) {
            return verifyMembership(
                    work, candidate.getTaskId(), work.payload().knownGroupJid(), now);
        }
        return protocolExecutor.join(candidate, work);
    }

    private PullTaskManagerJoinOutcome verifyMembership(
            PullTaskManagerJoinWork work, long taskId, String groupJid, long now) {
        String targetJid = WhatsappJids.userJid(work.payload().account().wsPhone());
        PullTaskMemberQueryResult query = memberQueryAwaitService.readOrDefer(
                work.tenantId(), new PullTaskMemberQueryRequest(
                        taskId, work.executionId(),
                        "manager-join-membership:" + work.groupAccountId() + ":" + work.actionId(),
                        PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP,
                        work.payload().account(), groupJid, java.util.List.of(targetJid)),
                work.expectedVersion(), work.lockOwner(),
                PullTaskExecutionStage.MANAGER_JOIN.code(), now);
        if (query.state() == PullTaskMemberQueryResult.State.PENDING) {
            return null;
        }
        if (query.state() == PullTaskMemberQueryResult.State.FAILED) {
            return PullTaskManagerJoinOutcome.unconfirmed(
                    groupJid,
                    PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name());
        }
        if (query.members().stream().anyMatch(PullTaskMemberFact::inGroup)) {
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

}
