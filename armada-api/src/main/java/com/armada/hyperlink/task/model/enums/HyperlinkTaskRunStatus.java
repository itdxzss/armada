package com.armada.hyperlink.task.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 超链任务运行状态数据库码。 */
public enum HyperlinkTaskRunStatus {
    /** 尚未开始。 */
    NOT_STARTED(0),
    /** 正在运行。 */
    RUNNING(1),
    /** 已完成终态。 */
    COMPLETED(2),
    /** 已暂停，可继续。 */
    PAUSED(3),
    /** 已停止终态。 */
    STOPPED(4);

    private final int code;

    HyperlinkTaskRunStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按数据库码解析状态。 */
    public static HyperlinkTaskRunStatus fromCode(Integer code) {
        for (HyperlinkTaskRunStatus status : values()) {
            if (Integer.valueOf(status.code).equals(code)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, "超链任务运行状态非法");
    }
}
