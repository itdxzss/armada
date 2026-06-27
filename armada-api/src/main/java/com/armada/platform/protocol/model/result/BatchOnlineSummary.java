package com.armada.platform.protocol.model.result;

/**
 * 批量上线投递结果汇总。
 *
 * @param requested     本批请求账号数
 * @param local         归属当前 worker 并尝试处理的账号数
 * @param remote        归属其它 worker 的账号数
 * @param accepted      已受理账号数
 * @param timeout       等待上线令牌超时账号数
 * @param proxyRequired 缺少代理账号数
 * @param error         其它错误账号数
 */
public record BatchOnlineSummary(
        int requested,
        int local,
        int remote,
        int accepted,
        int timeout,
        int proxyRequired,
        int error
) {
}
