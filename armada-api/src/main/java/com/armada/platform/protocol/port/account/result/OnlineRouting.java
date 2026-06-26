package com.armada.platform.protocol.port.account.result;

/**
 * 上线受理回执里的归属路由信息(防腐层语义)。
 *
 * <p>协议层按账号归属把请求路由到对应 worker;若当前 worker 非归属者会拒绝并回归属 endpoint。
 * 单 worker 测试环境恒为本地(local=true)。多 worker 转发由协议侧网关处理,不泄进业务编排。</p>
 *
 * @param ownerWorkerId   账号归属 worker 标识
 * @param ownerEndpoint   归属 worker 的访问地址;本地归属时可能为 null
 * @param currentWorkerId 实际受理本次请求的 worker 标识
 * @param local           是否由本地 worker 受理(true=归属即本地)
 */
public record OnlineRouting(
        String ownerWorkerId,
        String ownerEndpoint,
        String currentWorkerId,
        boolean local
) {
}
