package com.armada.group.model.vo;

/**
 * 群链接健康检查候选。
 *
 * <p>由后台巡检 SQL 跨租户选出:每个候选都已具备群 JID 和一个在线的我方在群账号,
 * 可以直接发往协议层 worker 执行 {@code groupMetadata}。</p>
 *
 * @param tenantId          群链接所属租户
 * @param groupLinkId       group_link.id
 * @param groupJid          WhatsApp 群 JID
 * @param accountId         本地操作账号 ID
 * @param protocolAccountId 协议层账号句柄
 */
public record GroupLinkHealthCheckCandidate(
        Long tenantId,
        Long groupLinkId,
        String groupJid,
        Long accountId,
        String protocolAccountId
) {
}
