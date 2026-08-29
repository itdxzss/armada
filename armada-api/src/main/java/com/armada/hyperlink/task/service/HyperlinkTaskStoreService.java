package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** 任务域本地聚合持久化入口；不跨业务子域。 */
@Service
public class HyperlinkTaskStoreService {
    private static final long POLL_AFTER_MS = 1000L;
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskContentMapper contentMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;

    public HyperlinkTaskStoreService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskContentMapper contentMapper, HyperlinkTaskRuntimeMapper runtimeMapper) {
        this.taskMapper = taskMapper;
        this.contentMapper = contentMapper;
        this.runtimeMapper = runtimeMapper;
    }

    public void insert(HyperlinkTask task, HyperlinkTaskContent content, HyperlinkTaskRuntime runtime) {
        taskMapper.insert(task);
        content.setHyperlinkTaskId(task.getId());
        runtime.setHyperlinkTaskId(task.getId());
        contentMapper.insert(content);
        runtimeMapper.insert(runtime);
    }

    public void update(HyperlinkTask task, HyperlinkTaskContent content, int expectedVersion) {
        if (taskMapper.updateConfig(task, expectedVersion) != 1) { throw stateConflict(); }
        contentMapper.update(content);
    }

    public HyperlinkTask requireTask(long taskId) {
        HyperlinkTask task = taskMapper.selectById(taskId);
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在"); }
        return task;
    }

    public HyperlinkTaskRuntime requireRuntime(long taskId) {
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskId(taskId);
        if (runtime == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务状态不存在"); }
        return runtime;
    }

    public HyperlinkTaskContent requireContent(long taskId) {
        HyperlinkTaskContent content = contentMapper.selectByTaskId(taskId);
        if (content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务内容不存在");
        }
        return content;
    }

    public void incrementVersion(long taskId, int expectedVersion, long now) {
        if (taskMapper.incrementVersion(taskId, expectedVersion, now) != 1) { throw stateConflict(); }
    }

    public boolean transition(long taskId, boolean expectedEnabled, int expectedRunStatus,
            boolean enabled, int runStatus, int provisionStatus, long now) {
        return runtimeMapper.transition(taskId, expectedEnabled, expectedRunStatus, enabled,
                runStatus, provisionStatus, now) == 1;
    }

    public boolean beginRebuild(long taskId, boolean targetEnabled, long now) {
        return runtimeMapper.beginRebuild(taskId, targetEnabled, now) == 1;
    }

    /** 将准备失败的原作业恢复为处理中，不生成新 claim 或新计费预约。 */
    public boolean resumeProvisioning(long taskId, long now) {
        return runtimeMapper.resumeProvisioning(taskId, now) == 1;
    }

    public HyperlinkTaskMutationReceiptVO receipt(long taskId) {
        return receipt(requireTask(taskId), requireRuntime(taskId));
    }

    public HyperlinkTaskMutationReceiptVO receipt(HyperlinkTask task, HyperlinkTaskRuntime runtime) {
        HyperlinkProvisionStatus provision = HyperlinkProvisionStatus.fromCode(runtime.getProvisionStatus());
        return new HyperlinkTaskMutationReceiptVO(task.getId(), provision,
                Boolean.TRUE.equals(runtime.getEnabled()), runtime.getRunStatus(), task.getVersion(),
                provision == HyperlinkProvisionStatus.PROCESSING ? POLL_AFTER_MS : null,
                runtime.getFailureCode(), runtime.getFailureReason());
    }

    private BusinessException stateConflict() {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT);
    }
}
