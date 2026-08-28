package com.armada.account.contact.service;

import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;

/** 账号通讯录采集服务。 */
public interface AccountContactSyncService {

    /**
     * 强制向协议层重拉一次通讯录并落快照。
     *
     * @param accountId 账号 ID
     * @param source 触发来源
     * @return 同步结果；协议失败时 succeeded 为 false 且不动已有快照
     */
    AccountContactSyncResult syncNow(Long accountId, ContactSyncSource source);

    /**
     * 快照过期才重拉，未过期直接返回现有计数。
     *
     * @param accountId 账号 ID
     * @param source 触发来源
     * @return 同步结果
     */
    AccountContactSyncResult syncIfStale(Long accountId, ContactSyncSource source);
}
