package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.port.CreatorAccountDeletionPort;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskStandardStatus;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久发送意图之后的最终 STOP 栅栏；只在有界 POST 期间持有父任务锁。 */
@Service
public class PullTaskCreatorDeletionDispatchService {
    private final PullTaskCreatorDeletionResources resources;
    private final CreatorAccountDeletionPort protocol;

    /** 创建与任务停止共用行锁的单次发送入口。 */
    public PullTaskCreatorDeletionDispatchService(PullTaskCreatorDeletionResources resources,
            CreatorAccountDeletionPort protocol) {
        this.resources = resources;
        this.protocol = protocol;
    }

    /**
     * 必须由首次 claimSubmission 成功的处理器调用；重启/恢复流程禁止调用。
     * 父任务锁覆盖有界 POST，保证 STOP 生效后没有新的删除派发；清理查询不持此锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatorDeletionResult send(PullTaskCreatorDeletionWork work, CreatorDeletionCommand command) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(work.execution().getTenantId());
            PullTask parent = resources.tasks().selectLifecycleForUpdate(work.execution().getTaskId());
            PullTaskGroupExecution current = resources.executions().selectByIdForUpdate(work.execution().getId());
            // 外部群查询可能耗尽租约，必须在取得最终行锁后读取本机当前时间。
            long dispatchAt = System.currentTimeMillis();
            if (parent == null || !PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                    || current == null || !Objects.equals(current.getManualPaused(), 0)
                    || !Objects.equals(current.getVersion(), work.execution().getVersion())
                    || !Objects.equals(current.getLockOwner(), work.execution().getLockOwner())
                    || current.getLockExpiresAt() == null || current.getLockExpiresAt() <= dispatchAt) {
                return new CreatorDeletionResult(command.operationId(), command.identityHash(),
                        "UNKNOWN", null, null, "TASK_STOPPED_OR_LEASE_LOST");
            }
            return protocol.delete(command);
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }
}
