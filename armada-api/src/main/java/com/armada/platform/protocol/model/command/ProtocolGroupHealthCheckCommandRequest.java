package com.armada.platform.protocol.model.command;

/**
 * 群链接健康检查协议命令请求。
 *
 * <p>该命令由 Armada 定时巡检生成,发往协议层 master,再按 {@code protocolAccountId}
 * 路由到持有该账号 socket 的 worker。payload 只携带群链接主键、群 JID 和操作账号引用,
 * 不复制凭据、代理密码等敏感数据。</p>
 *
 * @param tenantId          群链接所属租户 ID,用于健康事件回写时恢复租户上下文
 * @param groupLinkId       Armada 本地 group_link.id
 * @param groupJid          WhatsApp 群 JID
 * @param accountId         Armada 本地操作账号 ID
 * @param protocolAccountId 协议层账号句柄,也是 master owner 路由 key
 * @param source            命令来源,用于排查和审计
 */
public record ProtocolGroupHealthCheckCommandRequest(
        Long tenantId,
        Long groupLinkId,
        String groupJid,
        Long accountId,
        String protocolAccountId,
        String source
) {
}
