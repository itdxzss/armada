package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * Armada 写入协议 outbox 前使用的统一进群命令模型。
 *
 * <p>同一模型覆盖 Web 和 Android，{@code protocolBackend} 决定 Kafka topic，
 * {@code protocolAccountId} 作为 key 保证同账号命令有序。这里只保存执行引用和邀请码，不携带账号凭据。</p>
 *
 * @param tenantId 任务所属租户 ID
 * @param joinTaskId 进群任务 ID，用于批次归组和排查
 * @param joinTaskResultId 单账号单链接明细 ID，也是 outbox 聚合 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议层账号 ID，同时作为 Kafka key
 * @param wsPhone WhatsApp 手机号快照，供协议层校验和日志脱敏展示
 * @param protocolBackend 协议后端；决定 Web 或 Android 命令 topic
 * @param inviteCode 已从群链接中提取并校验的邀请码
 * @param attemptNo 当前业务尝试序号，从 1 开始
 * @param source 命令来源，进群任务固定为 join_task
 */
public record ProtocolGroupJoinCommandRequest(
        Long tenantId,
        Long joinTaskId,
        Long joinTaskResultId,
        Long accountId,
        String protocolAccountId,
        String wsPhone,
        ProtocolBackend protocolBackend,
        String inviteCode,
        int attemptNo,
        String source
) {
    /** 批量进群任务写入协议命令时使用的固定来源标识。 */
    public static final String SOURCE_JOIN_TASK = "join_task";
}
