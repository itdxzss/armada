package com.armada.platform.protocol.model.command;

/**
 * 账号当前群列表同步协议命令请求。
 *
 * <p>该命令由 Armada 定时巡检生成,发往协议层 master,再按 {@code protocolAccountId}
 * 路由到持有账号 socket 的 worker 执行 listParticipating。payload 只携带本地账号引用,
 * 不包含凭据、代理密码等敏感数据。</p>
 *
 * @param tenantId          账号所属租户 ID,用于结果事件回写时恢复租户上下文
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄,也是 master owner 路由 key
 * @param source            命令来源,用于排查和审计
 */
public record ProtocolAccountGroupSyncCommandRequest(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String source
) {
}
