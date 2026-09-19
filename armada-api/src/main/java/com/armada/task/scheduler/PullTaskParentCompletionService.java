package com.armada.task.scheduler;

import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.task.service.impl.PullTaskGroupRetryService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在执行行进入终态的同一事务内聚合父任务完成状态。 */
@Service
public class PullTaskParentCompletionService {

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final GroupDataPackageTaskProjectionService dataPackages;
    private final PullTaskGroupRetryService groupRetryService;

    /**
     * @param taskMapper 父任务 Mapper
     * @param executionMapper 执行行 Mapper
     * @param dataPackages 料子数据包状态投影
     * @param groupRetryService 群级失败的整份料子换群服务
     */
    public PullTaskParentCompletionService(
            PullTaskMapper taskMapper,
            PullTaskGroupExecutionMapper executionMapper,
            GroupDataPackageTaskProjectionService dataPackages,
            PullTaskGroupRetryService groupRetryService) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.dataPackages = dataPackages;
        this.groupRetryService = groupRetryService;
    }

    /** 先为可换群的失败建立下一次执行；否则结算当前执行并聚合父任务终态。 */
    @Transactional(rollbackFor = Exception.class)
    public void completeIfTerminalByExecutionId(long executionId, long now) {
        PullTaskGroupExecution execution = executionMapper.selectByIdForUpdate(executionId);
        if (execution == null || execution.getTaskId() == null) {
            throw new IllegalStateException("终态执行行不存在");
        }
        if (groupRetryService.retryIfEligible(execution, now)) {
            return;
        }
        // 单执行终态立即结算，不等待兄弟执行结束，也不扩锁其他任务或执行行。
        if (terminal(execution) && execution.getSourcePackageId() != null) {
            dataPackages.synchronizeExecution(executionId);
        }
        completeIfTerminalByTaskId(execution.getTaskId(), now);
    }

    /** 全部执行行终态时完成仍处于执行中的父任务；暂停父任务等待人工恢复。 */
    public void completeIfTerminalByTaskId(long taskId, long now) {
        PullTask parent = taskMapper.selectLifecycle(taskId);
        if (parent == null) {
            throw new IllegalStateException("终态执行行的父任务不存在");
        }
        if (PullTaskStandardStatus.COMPLETED.name().equals(parent.getStatus())) {
            return;
        }
        if (!PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())) {
            return;
        }
        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(parent.getId());
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !terminal(row))) {
            return;
        }
        if (taskMapper.updateStatusWithVersion(
                parent.getId(), PullTaskStandardStatus.EXECUTING.name(),
                PullTaskStandardStatus.COMPLETED.name(), parent.getVersion(),
                null, now, now) == 1) {
            return;
        }
        PullTask current = taskMapper.selectLifecycle(parent.getId());
        if (current == null
                || !PullTaskStandardStatus.COMPLETED.name().equals(current.getStatus())) {
            throw new IllegalStateException("父任务完成状态发生并发变化");
        }
    }

    private static boolean terminal(PullTaskGroupExecution row) {
        return row.getExecutionStatus() == PullTaskExecutionStatus.COMPLETED.code()
                || row.getExecutionStatus() == PullTaskExecutionStatus.FAILED.code()
                || row.getExecutionStatus() == PullTaskExecutionStatus.ABANDONED.code();
    }
}
