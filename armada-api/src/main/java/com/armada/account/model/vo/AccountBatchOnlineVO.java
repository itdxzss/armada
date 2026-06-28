package com.armada.account.model.vo;

import java.util.List;

/**
 * 批量上线受理结果。
 *
 * <p>这些字段表达的是"上线命令是否写入本地 outbox",不是账号最终在线状态。
 * 最终 ONLINE/OFFLINE 仍以后续 Kafka 状态回填为准。</p>
 *
 * @param requested     请求账号数
 * @param submitted     实际写入 outbox 的账号数
 * @param accepted      outbox 已受理账号数
 * @param timeout       outbox 阶段固定为 0
 * @param proxyRequired outbox 阶段固定为 0
 * @param error         outbox 阶段固定为 0
 * @param remote        归属其它协议 worker 的账号数
 * @param elapsedMs     outbox 阶段固定为 0
 * @param results       本 worker 已处理账号结果
 * @param remoteRoutes  远端 owner 路由信息
 */
public record AccountBatchOnlineVO(
        int requested,
        int submitted,
        int accepted,
        int timeout,
        int proxyRequired,
        int error,
        int remote,
        long elapsedMs,
        List<AccountBatchOnlineItemVO> results,
        List<AccountBatchOnlineRemoteRouteVO> remoteRoutes
) {
}
