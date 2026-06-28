package com.armada.marketing.model.enums;

/**
 * 营销任务状态码,与 V014 marketing_task.status COMMENT 保持一致。
 */
public enum MarketingTaskStatus {

    /** 待启动/未发送。 */
    PENDING(1),

    /** 发送中。 */
    SENDING(2),

    /** 发送成功。 */
    SUCCESS(3),

    /** 发送失败。 */
    FAILED(4),

    /** 已停止。 */
    STOPPED(5),

    /** 部分失败。 */
    PARTIAL_FAILED(6);

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
