package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountGroupSyncCommandService;
import com.armada.account.service.AccountStateChangedEvent;
import org.springframework.stereotype.Component;

/** 账号首次上线后按持久化 baseline 状态下发一次全量群同步。 */
@Component
public class InitialAccountGroupSyncSideEffect implements AccountStateChangedSideEffect {

    private static final String STATE_ONLINE = "ONLINE";

    private final AccountGroupSyncCommandService syncCommands;

    /**
     * 创建首次上线群同步副作用。
     *
     * @param syncCommands 账号群同步命令服务
     */
    public InitialAccountGroupSyncSideEffect(AccountGroupSyncCommandService syncCommands) {
        this.syncCommands = syncCommands;
    }

    /**
     * ONLINE 时交给数据库 baseline 状态决定是否下发全量命令；其他状态直接跳过。
     *
     * @param account 已完成状态收敛的账号
     * @param event 协议账号状态事件
     * @param occurredAt 状态发生时间(epoch 毫秒)
     */
    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt) {
        if (!STATE_ONLINE.equalsIgnoreCase(event.to())) {
            return;
        }
        syncCommands.enqueueInitialBaselineSync(account, occurredAt);
    }
}
