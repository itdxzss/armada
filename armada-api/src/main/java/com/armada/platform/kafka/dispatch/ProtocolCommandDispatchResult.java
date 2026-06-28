package com.armada.platform.kafka.dispatch;

/**
 * 协议命令 outbox dispatch 结果。
 *
 * @param selected 本轮扫描到的 PENDING 行数
 * @param locked   本轮实际抢占成功行数
 * @param sent     Kafka ack 成功并标记 SENT 行数
 * @param retried  发送失败并释放回 PENDING 行数
 * @param dead     发送失败并标记 DEAD 行数
 */
public record ProtocolCommandDispatchResult(
        int selected,
        int locked,
        int sent,
        int retried,
        int dead
) {

    /**
     * 创建空 dispatch 结果。
     *
     * @return 空结果
     */
    public static ProtocolCommandDispatchResult empty() {
        return new ProtocolCommandDispatchResult(0, 0, 0, 0, 0);
    }

    /**
     * 合并两段 dispatch 结果。
     *
     * @param other 另一段结果
     * @return 合并后的结果
     */
    public ProtocolCommandDispatchResult plus(ProtocolCommandDispatchResult other) {
        if (other == null) {
            return this;
        }
        return new ProtocolCommandDispatchResult(
                selected + other.selected,
                locked + other.locked,
                sent + other.sent,
                retried + other.retried,
                dead + other.dead);
    }

    /**
     * 判断本轮是否处理过任何 outbox 行。
     *
     * @return true=处理过行
     */
    public boolean hasWork() {
        return selected > 0 || locked > 0 || sent > 0 || retried > 0 || dead > 0;
    }
}
