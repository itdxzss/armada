package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.command.CreatorDeletionObservationRequest;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.port.CreatorAccountDeletionPort;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskCreatorDeletionStatus;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 一轮只做有界新鲜查询/一次首次删除；无 sleep，不阻塞调度等待清理。 */
@Component
public class PullTaskCreatorDeletionProcessor {
    private static final Logger log = LoggerFactory.getLogger(PullTaskCreatorDeletionProcessor.class);
    private final PullTaskCreatorDeletionTransactionService transactions;
    private final CreatorAccountDeletionPort protocol;
    private final PullTaskCreatorDeletionDispatchService dispatch;

    /** 注销协议调用始终在已提交短事务之后发生。 */
    public PullTaskCreatorDeletionProcessor(PullTaskCreatorDeletionTransactionService transactions,
            CreatorAccountDeletionPort protocol, PullTaskCreatorDeletionDispatchService dispatch) {
        this.transactions = transactions;
        this.protocol = protocol;
        this.dispatch = dispatch;
    }

    /** 两个注销阶段共用账本；阶段或 HTTP 结果不能代替证据完成。 */
    public PullTaskExecutionDispatchResult process(PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!Objects.equals(candidate.getLockOwner(), lockOwner)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Optional<PullTaskCreatorDeletionWork> prepared = transactions.prepare(candidate, now);
        if (prepared.isEmpty()) { return PullTaskExecutionDispatchResult.DEFERRED; }
        PullTaskCreatorDeletionWork work = prepared.get();
        if (Objects.equals(work.deletion().getStatus(), PullTaskCreatorDeletionStatus.RESERVED.code())) {
            return submitOnce(work, now);
        }
        return reconcile(work, now);
    }

    private PullTaskExecutionDispatchResult submitOnce(PullTaskCreatorDeletionWork work, long now) {
        CreatorDeletionObservation proof;
        try { proof = protocol.observe(observationRequest(work)); }
        catch (RuntimeException exception) {
            log.info("注销前管理员实时核验不可用 executionId={}", work.execution().getId());
            String classification = exception instanceof com.armada.platform.protocol.exception.ProtocolException protocolFailure
                    ? protocolFailure.errorCode().name() : "QUERY_FAILED";
            return transactions.reject(work.execution(), "接管管理号实时群查询失败（" + classification + "），未执行注销", now);
        }
        if (!PullTaskCreatorDeletionProof.takeover(work, proof, now)) {
            return transactions.reject(work.execution(), "未实时确认独立管理号具备管理员权限、建群者身份或正常群状态，未执行注销", now);
        }
        if (!transactions.claimSubmission(work, proof, now)) {
            return PullTaskExecutionDispatchResult.DEFERRED;
        }
        CreatorDeletionResult result = null;
        try { result = dispatch.send(work, command(work)); }
        catch (RuntimeException exception) {
            // 删除异常可能已写入 socket，不记录包含授权/号码的异常正文，也绝不重发。
            log.info("永久注销提交结果未知 executionId={}，后续仅查询原操作", work.execution().getId());
        }
        return transactions.record(work, result, null, now);
    }

    private PullTaskExecutionDispatchResult reconcile(PullTaskCreatorDeletionWork work, long now) {
        CreatorDeletionResult result = null;
        CreatorDeletionObservation observation = null;
        try {
            result = protocol.query(command(work));
            if (PullTaskCreatorDeletionProof.matches(work.deletion(), result) && result.accepted()) {
                observation = protocol.observe(observationRequest(work));
            }
        } catch (RuntimeException exception) {
            log.info("永久注销对账查询暂不可用 executionId={}", work.execution().getId());
        }
        return transactions.record(work, result, observation, now);
    }

    private static CreatorDeletionCommand command(PullTaskCreatorDeletionWork work) {
        PullTaskCreatorDeletion row = work.deletion();
        return new CreatorDeletionCommand(row.getTenantId(), row.getTaskId(), row.getGroupExecutionId(),
                work.creator(), row.getCreatorIdentityHash(), row.getCreateOperationId(), row.getOperationId());
    }

    private static CreatorDeletionObservationRequest observationRequest(PullTaskCreatorDeletionWork work) {
        return new CreatorDeletionObservationRequest(work.manager(), work.creator(), work.execution().getGroupJid());
    }
}
