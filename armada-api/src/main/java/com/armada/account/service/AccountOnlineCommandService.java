package com.armada.account.service;

import com.armada.account.model.command.AccountLifecycleCommandItem;
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
     * 代理失败后自动重新上线账号,并把刚失败的上线尝试 ID 串到新命令里。
     *
     * <p>{@code failedOnlineAttemptId} 来自协议层 {@code account.state_changed}。
     * 它比离线诊断日志更早到达,用于避免 Kafka 事件顺序导致的 attempt 链路断裂。
     * 当该字段为空时,实现会退回读取最近一次诊断日志。</p>
     *
     * @param accountId              armada 账号主键
     * @param failedOnlineAttemptId  刚失败的上线尝试 ID,可空
     * @return outbox 上线命令受理回执
     * @throws BusinessException 当账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountOnlineVO reonlineAfterProxyFailure(Long accountId, String failedOnlineAttemptId);

    /**
     * 代理失败后换 IP 重上线，并排除协议事件明确指出的失败代理。
     *
     * @param accountId             Armada 账号主键
     * @param failedOnlineAttemptId 刚失败的上线尝试 ID，可空
     * @param failedProxyId         刚失败的代理 ID，可空；非空时本次分配必须排除
     * @return outbox 上线命令受理回执；账号已不再满足 PROXY_FAILED 恢复条件时 accepted=false
     */
    AccountOnlineVO reonlineAfterProxyFailure(
            Long accountId,
            String failedOnlineAttemptId,
            Long failedProxyId);

    /**
     * 批量发起一键抢登。
     *
     * <p>只有全部账号当前为“被抢登”且未禁言时才允许进入抢登中。实现会先把账号状态改为抢登中,
     * 再复用批量上线 outbox 编排。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return outbox 批量上线命令受理汇总
     * @throws BusinessException 当账号列表为空、存在非被抢登账号或上线前置条件不满足时抛出
     */
    AccountBatchOnlineVO takeoverBatch(List<Long> accountIds);

    /**
     * 抢登中账号在再次离线或被抢登后自动续上线。
     *
     * <p>实现必须重新读取账号状态并检查禁言与短窗口冷却,确保用户手动停止或状态变化后不再重投。</p>
     *
     * @param accountId              armada 账号主键
     * @param failedOnlineAttemptId  刚失败的上线尝试 ID,可空
     * @param source                 本次续上线来源,如 login_replaced_takeover/offline_takeover
     * @return outbox 上线命令受理回执;不满足续上线条件时 accepted=false
     */
    AccountOnlineVO reonlineForTakeover(Long accountId, String failedOnlineAttemptId, String source);

    /**
     * 批量发起账号上线。
     *
     * <p>一次最多 1000 个账号。实现会批量加载账号与凭据、批量分配代理,
     * 然后批量写入协议命令 outbox。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return outbox 批量上线命令受理回执
     * @throws BusinessException 当账号列表、账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountBatchOnlineVO onlineBatch(List<Long> accountIds);

    /**
     * 批量发起账号上线,由请求项携带协议后端路由。
     *
     * <p>用于账号列表前端已经拿到协议后端的场景。实现仍会读取账号、凭据、状态和代理完成上线前置校验,
     * 但生成协议命令时使用请求项里的协议后端。</p>
     *
     * @param accounts 账号生命周期命令项
     * @return outbox 批量上线命令受理回执
     * @throws BusinessException 当账号列表、协议后端、账号、凭据或代理分配不满足上线前置条件时抛出
     */
    AccountBatchOnlineVO onlineBatchWithProtocolBackends(List<AccountLifecycleCommandItem> accounts);

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
     * <p>一次最多 1000 个账号。实现会批量加载账号并写入协议命令 outbox,
     * 不在请求线程直接修改登录状态或释放代理绑定。</p>
     *
     * @param accountIds armada 账号主键列表
     * @return outbox 批量下线命令受理回执
     * @throws BusinessException 当账号列表或账号不满足下线前置条件时抛出
     */
    AccountBatchOnlineVO offlineBatch(List<Long> accountIds);

    /**
     * 批量发起账号下线,由请求项携带协议后端路由。
     *
     * @param accounts 账号生命周期命令项
     * @return outbox 批量下线命令受理回执
     * @throws BusinessException 当账号列表、协议后端或账号不满足下线前置条件时抛出
     */
    AccountBatchOnlineVO offlineBatchWithProtocolBackends(List<AccountLifecycleCommandItem> accounts);
}
