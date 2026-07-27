package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;

import java.util.List;

/**
 * Web 协议多账号批量查群端口。
 *
 * <p>调用方必须已经持有 Web {@code protocolAccountId}，此端口不参与账号后端路由。</p>
 */
public interface AccountParticipatingGroupBatchPort {

    /**
     * 批量查询多个 Web 协议账号当前参与的群。
     *
     * @param protocolAccountIds Web 协议层账号句柄
     * @param concurrency 单次协议请求并发数
     * @return 协议层返回的逐账号查群结果
     */
    List<AccountParticipatingGroupResult> listBatch(
            List<String> protocolAccountIds,
            int concurrency);
}
