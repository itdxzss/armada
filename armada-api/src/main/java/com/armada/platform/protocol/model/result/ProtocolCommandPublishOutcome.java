package com.armada.platform.protocol.model.result;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;

/**
 * 单条协议命令发送结果。
 *
 * <p>批量 publisher 先批量补全命令 payload,再按有界窗口异步发送 Kafka。结果顺序与输入 outbox 行一致；
 * dispatcher 按窗口批量回写成功状态，少量失败结果继续逐行进入 RETRY/DEAD。</p>
 */
public record ProtocolCommandPublishOutcome(
        ProtocolCommandOutbox row,
        ProtocolCommandPublishResult result,
        RuntimeException error
) {

    public static ProtocolCommandPublishOutcome success(ProtocolCommandOutbox row,
                                                        ProtocolCommandPublishResult result) {
        return new ProtocolCommandPublishOutcome(row, result, null);
    }

    public static ProtocolCommandPublishOutcome failure(ProtocolCommandOutbox row, RuntimeException error) {
        return new ProtocolCommandPublishOutcome(row, null, error);
    }

    public boolean succeeded() {
        return error == null;
    }
}
