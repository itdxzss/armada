package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 账号当前群列表同步协议命令请求。
 *
 * <p>该命令由 Armada 定时巡检生成。Web 号发往协议层 master,再按 {@code protocolAccountId}
 * 路由到持有账号 socket 的 worker 执行 listParticipating;安卓号发往安卓生命周期 topic,
 * 由持有该号的 fleet 节点重新汇报群列表。</p>
 *
 * <p>走错 topic 的后果不是报错而是静默丢失:Web master 查不到安卓号的 owner,
 * 该命令类型又没有 owner 缺失兜底回执,只会记一条 warn 日志,该号的群列表因此永远不被刷新。</p>
 *
 * <p>payload 只携带本地账号引用,不包含凭据、代理密码等敏感数据。</p>
 *
 * @param tenantId          账号所属租户 ID,用于结果事件回写时恢复租户上下文
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄,也是 master owner 路由 key
 * @param protocolBackend   协议后端,决定命令发往哪个 topic
 * @param source            命令来源,用于排查和审计
 */
public record ProtocolAccountGroupSyncCommandRequest(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        ProtocolBackend protocolBackend,
        String source
) {
}
