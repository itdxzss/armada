package com.armada.platform.protocol.model.result;

/**
 * 协议命令 Kafka 发送结果。
 *
 * <p>Slice 4 只确认 KafkaTemplate 已接受发送并完成 ack;状态落库由后续 scheduler 切片负责。</p>
 *
 * @param commandId 发送的命令 ID
 * @param topic     Kafka topic
 * @param kafkaKey  Kafka key
 */
public record ProtocolCommandPublishResult(
        String commandId,
        String topic,
        String kafkaKey
) {
}
