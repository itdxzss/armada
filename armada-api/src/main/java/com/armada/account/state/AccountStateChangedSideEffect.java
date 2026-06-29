package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;

/**
 * 账号状态变更后的业务结算扩展点。
 *
 * <p>{@link AccountStateEventService} 负责先收敛通用账号状态,再调用本扩展点处理依赖账号状态事件的业务单据。
 * 实现类必须保持幂等:没有自己关心的待结算数据时直接 no-op。</p>
 */
public interface AccountStateChangedSideEffect {

    /**
     * 在账号状态事件完成通用状态收敛后执行领域附加结算。
     *
     * @param account    已匹配到的未软删账号
     * @param event      协议层状态变更事件
     * @param occurredAt 本次事件实际用于落库的发生时间(epoch 毫秒)
     */
    void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt);
}
