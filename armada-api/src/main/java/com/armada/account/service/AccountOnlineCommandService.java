package com.armada.account.service;

import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.shared.exception.BusinessException;
import java.util.List;

/**
 * 账号生命周期命令应用服务。
 *
 * <p>负责从账号 ID 出发,把上线/下线命令写入协议命令 outbox,
 * 由 Kafka 消费链路异步执行协议账号生命周期动作。</p>
 */
public interface AccountOnlineCommandService {

    /**
     * 发起单账号上线。
     *
     * @param accountId armada 账号主键
     * @return outbox 上线命令受理回执
     * @throws BusinessException 当账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountOnlineVO online(Long accountId);

    /**
     * 代理失败后自动重新上线账号。
     *
     * <p>调用方必须先释放账号当前绑定 IP。实现会重新分配一条可用代理并写入上线 outbox,
     * 返回值仍只表示命令已受理,不代表账号已经 ONLINE。</p>
     *
     * @param accountId armada 账号主键
     * @return outbox 上线命令受理回执
     * @throws BusinessException 当账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountOnlineVO reonlineAfterProxyFailure(Long accountId);

    /**
     * 批量发起账号上线。
     *
     * <p>一次最多 500 个账号。实现会批量加载账号与凭据、批量分配代理,
     * 然后批量写入协议命令 outbox。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return outbox 批量上线命令受理回执
     * @throws BusinessException 当账号列表、账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountBatchOnlineVO onlineBatch(List<Long> accountIds);

    /**
     * 对指定代理绑定的在线账号发起换 IP 重登。
     *
     * <p>调用方传入即将删除的代理 ID。实现会查询这些代理当前绑定账号,只筛选 login_state=ONLINE 的账号,
     * 为这些账号重新分配代理并写入上线 outbox。离线账号不做任何处理。</p>
     *
     * @param proxyIds 即将删除的代理 ID 列表
     * @return 被重登的在线账号 outbox 受理结果;没有在线账号时返回零计数结果
     * @throws BusinessException 当在线账号重登所需账号、凭据或代理分配不满足前置条件时抛出
     */
    AccountBatchOnlineVO reloginOnlineAccountsByProxyIds(List<Long> proxyIds);

    /**
     * 批量发起账号下线。
     *
     * <p>一次最多 500 个账号。实现会批量加载账号并写入协议命令 outbox,
     * 不在请求线程直接修改登录状态或释放代理绑定。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return outbox 批量下线命令受理回执
     * @throws BusinessException 当账号列表或账号不满足下线前置条件时抛出
     */
    AccountBatchOnlineVO offlineBatch(List<Long> accountIds);
}
