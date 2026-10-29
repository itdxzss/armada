package com.armada.platform.protocol.model.result;

/**
 * 安卓主设备输入关联设备配对码的结果。
 *
 * @param accepted 安卓层已发送 primary_hello，等待关联设备完成配对
 * @param reason 未受理时的原因：ACCOUNT_OFFLINE 主设备不在线；EXPIRED 配对请求已失效；
 *               NO_PENDING 关联设备的配对请求尚未到达，可短暂重试；AMBIGUOUS 有多个未开始的配对请求，必须停止；
 *               SEND_FAILED 发送阶段失败且可能已有副作用，不得重提；UNKNOWN 无法识别的响应。受理时为 null
 */
public record CompanionPairCodeResult(boolean accepted, String reason) {

    /** 尚无可确认的配对请求，调用方可在短窗口内重试。 */
    public static final String NO_PENDING = "NO_PENDING";

    /**
     * 是否允许在短窗口内重提同一个配对码。
     *
     * @return 只有 NO_PENDING 可以重试
     */
    public boolean retryable() {
        return !accepted && NO_PENDING.equals(reason);
    }
}
