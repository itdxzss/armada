package com.armada.hyperlink.task.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 超链任务轮次状态数据库码。 */
public enum HyperlinkTaskRoundStatus {
    /** 等待计划时间。 */
    PLANNED(1),
    /** 正在固化本轮账号集合。 */
    SELECTING(2),
    /** 已选号，等待派发。 */
    READY(3),
    /** 正在派发。 */
    DISPATCHING(4),
    /** 已派发完，等待在途结果。 */
    WAITING_RESULT(5),
    /** 本轮自然完成。 */
    COMPLETED(6),
    /** 随任务暂停。 */
    PAUSED(7),
    /** 随任务停止或重建而取消。 */
    CANCELED(8),
    /** 轮次发生不可恢复失败。 */
    FAILED(9),
    /** 本轮没有匹配账号。 */
    NO_ACCOUNT(10);

    private final int code;

    HyperlinkTaskRoundStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按数据库码解析轮次状态。 */
    public static HyperlinkTaskRoundStatus fromCode(Integer code) {
        for (HyperlinkTaskRoundStatus status : values()) {
            if (Integer.valueOf(status.code).equals(code)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT, "超链任务轮次状态非法");
    }
}
