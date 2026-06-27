package com.armada.account.service;

import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import java.util.List;

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

    /**
     * 批量发起账号上线。
     *
     * <p>实现必须把多个 plan 组装成一次协议层 batch 请求,不能退化为逐账号调用
     * {@link com.armada.platform.protocol.port.AccountLifecyclePort#online(String, com.armada.platform.protocol.model.command.OnlineCommand)}。</p>
     *
     * @param plans     批量上线计划
     * @param maxWaitMs 协议层等待上线令牌的最长时间
     * @return 协议层批量上线受理回执
     */
    BatchOnlineAccepted onlineBatch(List<AccountOnlinePlan> plans, int maxWaitMs);
}
