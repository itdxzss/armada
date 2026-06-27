package com.armada.platform.protocol.model.command;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 协议命令 Kafka envelope。
 *
 * <p>Kafka 消息体使用统一 envelope 包住 outbox 元数据和业务 payload,便于协议层按
 * commandId 做幂等、按 commandType 分发、按 batchId/accountId 排查。</p>
 *
 * @param commandId         全局唯一命令 ID
 * @param batchId           批量命令归组 ID;单条命令可为空
 * @param commandType       命令类型,如 account.online.requested
 * @param aggregateType     聚合类型,如 ACCOUNT
 * @param aggregateId       聚合 ID;账号命令对应 account.id
 * @param protocolAccountId 协议层账号句柄
 * @param payload           轻量业务 payload,不包含凭据和代理密码
 */
public record ProtocolCommandEnvelope(
        String commandId,
        String batchId,
        String commandType,
        String aggregateType,
        Long aggregateId,
        String protocolAccountId,
        JsonNode payload
) {
}
