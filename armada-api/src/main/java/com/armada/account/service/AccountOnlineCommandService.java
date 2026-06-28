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
