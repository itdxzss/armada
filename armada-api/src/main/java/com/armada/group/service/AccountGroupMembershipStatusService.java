package com.armada.group.service;

import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMessageSendPermissionSnapshot;
import com.armada.group.model.vo.AccountGroupMembershipStatusSnapshot;
import java.util.List;

/** 账号群关系当前状态的跨域只读服务。 */
public interface AccountGroupMembershipStatusService {

    /**
     * 批量查询当前租户内账号群关系状态。
     *
     * @param lookups 账号 ID 与群 JID 复合键
     * @return 当前状态；关系不存在的键不返回行
     */
    List<AccountGroupMembershipStatusSnapshot> findCurrentStatuses(
            List<AccountGroupMembershipLookup> lookups);

    /**
     * 批量查询当前租户内账号群发言权限。
     *
     * @param lookups 账号 ID 与群 JID 复合键
     * @return 当前发言权限；权限或账号角色事实不足时允许值为空
     */
    List<AccountGroupMessageSendPermissionSnapshot> findCurrentMessageSendPermissions(
            List<AccountGroupMembershipLookup> lookups);

    /**
     * 应用协议层账号自身的精确群关系事实。
     *
     * @param event 精确关系事件
     */
    void applyMembershipChanged(AccountGroupMembershipChangedEvent event);
}
