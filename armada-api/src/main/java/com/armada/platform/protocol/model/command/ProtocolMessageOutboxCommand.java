package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 消息 backend 编码完成后交给通用 outbox 的内部命令。
 *
 * @param command 协议无关的原始消息命令
 * @param backend 目标协议后端
 * @param kafkaTopic 目标 Kafka topic
 * @param kafkaKey Kafka 分区键
 * @param payload backend 专属 wire payload
 */
public record ProtocolMessageOutboxCommand(
        MessageSendCommand command,
        ProtocolBackend backend,
        String kafkaTopic,
        String kafkaKey,
        Object payload
) {
}
