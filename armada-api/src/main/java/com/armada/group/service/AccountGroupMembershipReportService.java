package com.armada.group.service;

import com.armada.group.model.dto.AccountGroupsReportedEvent;

/**
 * 账号当前群列表回报落库服务。
 */
public interface AccountGroupMembershipReportService {

    /**
     * 应用协议层 {@code account.groups_reported} 回报事件。
     *
     * @param event 账号当前群列表回报事件
     */
    void applyGroupsReported(AccountGroupsReportedEvent event);
}
