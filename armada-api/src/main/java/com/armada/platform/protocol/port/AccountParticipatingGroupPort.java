package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import java.util.List;

/**
 * 协议层账号当前参与群实时查询端口。
 */
public interface AccountParticipatingGroupPort {

    /**
     * 批量查询多个协议账号当前参与的群。
     *
     * @param protocolAccountIds 协议层账号句柄
     * @param concurrency        单次协议请求并发数,最终由协议服务自行限制
     * @return 协议层返回的逐账号查群结果
     */
    List<AccountParticipatingGroupResult> listBatch(List<String> protocolAccountIds, int concurrency);
}
