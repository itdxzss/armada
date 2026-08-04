package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/** 在执行行进入终态的同一事务内聚合父任务完成状态。 */
@Service
public class PullTaskParentCompletionService {

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;

    /**
     * @param taskMapper 父任务 Mapper
     * @param executionMapper 执行行 Mapper
     */
    public PullTaskParentCompletionService(
            PullTaskMapper taskMapper,
            PullTaskGroupExecutionMapper executionMapper) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
    }

    /** 全部执行行进入终态时，以乐观锁把父任务从执行中推进为完成。 */
    public void completeIfTerminalByExecutionId(long executionId, long now) {
        PullTaskGroupExecution execution = executionMapper.selectById(executionId);
        if (execution == null || execution.getTaskId() == null) {
            throw new IllegalStateException("终态执行行不存在");
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
