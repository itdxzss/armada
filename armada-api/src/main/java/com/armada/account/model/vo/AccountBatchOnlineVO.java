package com.armada.account.model.vo;

import java.util.List;

/**
 * 批量上线受理结果。
 *
 * <p>这些字段表达的是"协议层是否接收/投递上线命令",不是账号最终在线状态。
 * 最终 ONLINE/OFFLINE 仍以后续 Kafka 状态回填为准。</p>
 *
 * @param requested     请求账号数
 * @param submitted     实际提交协议层账号数
 * @param accepted      协议层已受理账号数
 * @param timeout       等待协议层上线令牌超时账号数
 * @param proxyRequired 协议层认为缺代理账号数
 * @param error         协议层其它错误账号数
 * @param remote        归属其它协议 worker 的账号数
 * @param elapsedMs     协议层处理本批命令耗时
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
