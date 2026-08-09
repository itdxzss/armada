package com.armada.task.model.enums;

/** 普通群链接拉人波次类型。 */
public enum PullTaskPullWaveType {

    /** 初始波次：包含最初全部未执行参与者。 */
    INITIAL(1),

    /** 重试波次：仅包含上一波结算后的可重试参与者。 */
    RETRY(2);

    private final int code;

    PullTaskPullWaveType(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
