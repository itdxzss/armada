package com.armada.marketing.model.enums;

/**
 * 营销消息单次发送尝试状态。
 *
 * <p>attempt 先以 {@link #SUBMITTED} 落库,协议层发送完成后再由
 * {@code message.send_result_reported} 事件幂等更新为成功或失败。</p>
 */
public enum MarketingSendAttemptStatus {
    /** 已提交到协议 outbox,尚未收到协议层发送结果。 */
    SUBMITTED(0),

    /** 协议层已成功调用发送接口并返回 WhatsApp message id。 */
    SUCCESS(1),

    /** 协议层发送失败,reason_code/reason_message 记录失败原因。 */
    FAILED(2),

    /** 业务规则跳过发送,保留给后续在线检测/异常群跳过能力。 */
    SKIPPED(3);

    private final int code;

    MarketingSendAttemptStatus(int code) {
        this.code = code;
    }

    /** 数据库中持久化的 tinyint 枚举值。 */
    public int code() {
        return code;
    }
}
