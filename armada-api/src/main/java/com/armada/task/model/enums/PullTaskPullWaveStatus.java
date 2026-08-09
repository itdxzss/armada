package com.armada.task.model.enums;

/** 普通群链接拉人波次生命周期。 */
public enum PullTaskPullWaveStatus {

    /** 正按持久化时间逐个派发冻结调用。 */
    DISPATCHING(1),

    /** 所有调用已派发，正在等待全部参与者事实收口。 */
    COLLECTING(2),

    /** 全部参与者已结算，并已决定是否创建重试波次。 */
    SETTLED(3),

    /** 人工结束或执行终止时取消了尚未发布的波次工作。 */
    CANCELED(4);

    private final int code;

    PullTaskPullWaveStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /**
     * 判断波次是否仍占用执行行的活动波次槽位。
     *
     * @param code 数据库存储值
     * @return 派发中或收集中时为 true
     */
    public static boolean active(int code) {
        return code == DISPATCHING.code || code == COLLECTING.code;
    }
}
