package com.armada.account.service;

import com.armada.platform.protocol.port.account.result.OnlineAccepted;

/**
 * 账号上线编排服务。
 *
 * <p>负责把账号域准备好的凭据与代理端点组装成协议端口命令。
 * 协议层返回的 {@link OnlineAccepted} 只表示"已受理",不表示账号已经 ONLINE。</p>
 */
public interface AccountOnlineService {

    /**
     * 发起单账号上线。
     *
     * @param plan 单账号上线编排计划
     * @return 协议层上线受理回执
     */
    OnlineAccepted online(AccountOnlinePlan plan);
}
