package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

/** 后端唯一生命周期动作矩阵。 */
@Component
public class HyperlinkTaskStateMachine {

    /** 校验当前双状态并返回动作后的运行状态。 */
    public HyperlinkTaskRunStatus next(
            boolean enabled,
            HyperlinkTaskRunStatus current,
            HyperlinkTaskAction action) {
        if (current == null || action == null) {
            throw conflict();
        }
        if (action == HyperlinkTaskAction.START && current == HyperlinkTaskRunStatus.NOT_STARTED) {
            return HyperlinkTaskRunStatus.NOT_STARTED;
        }
        if (enabled && action == HyperlinkTaskAction.PAUSE
                && current == HyperlinkTaskRunStatus.RUNNING) {
            return HyperlinkTaskRunStatus.PAUSED;
        }
        if (enabled && action == HyperlinkTaskAction.RESUME
                && current == HyperlinkTaskRunStatus.PAUSED) {
            return HyperlinkTaskRunStatus.RUNNING;
        }
        if (enabled && action == HyperlinkTaskAction.STOP
                && (current == HyperlinkTaskRunStatus.RUNNING
                        || current == HyperlinkTaskRunStatus.PAUSED)) {
            return HyperlinkTaskRunStatus.STOPPED;
        }
        throw conflict();
    }

    private static BusinessException conflict() {
        return new BusinessException(
                ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                "当前超链任务状态不允许该动作");
    }
}
