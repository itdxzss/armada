package com.armada.marketing.model.enums;

/**
 * 普通群组营销任务五态生命周期,与最新 Flyway 状态列注释保持一致。
 */
public enum MarketingTaskStatus {

    /** 未启动；等待计划开始时间，账号已经由任务锁定。 */
    PENDING(1),

    /** 执行中；允许生成营销发送轮次。 */
    SENDING(2),

    /** 已暂停；任务可恢复，账号仍由当前任务持有。 */
    PAUSED(5),

    /** 已完成；包含正常到期和明确的异常终止，不可再次启动。 */
    COMPLETED(7),

    /** 已关闭；由运营手动关闭形成，不可恢复。 */
    CLOSED(8);

    private final int code;

    MarketingTaskStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * 建任务入参的发送状态/启动模式归一。第一阶段只改变任务状态,不触发真实发送。
     */
    public static MarketingTaskStatus fromStartMode(String startMode) {
        if ("IMMEDIATE".equalsIgnoreCase(startMode) || "立即启动".equals(startMode)) {
            return SENDING;
        }
        return PENDING;
    }
}
