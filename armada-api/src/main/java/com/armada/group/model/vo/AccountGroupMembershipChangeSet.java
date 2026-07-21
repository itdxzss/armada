package com.armada.group.model.vo;

import java.util.List;

/**
 * 一次账号当前群全量快照刷新结果。
 *
 * @param currentGroups 刷新后仍活跃的全部群
 * @param addedGroups   刷新前不存在、本次首次出现的群
 */
public record AccountGroupMembershipChangeSet(
        List<AccountGroupMembershipSnapshot> currentGroups,
        List<AccountGroupMembershipSnapshot> addedGroups
) {
    public AccountGroupMembershipChangeSet {
        currentGroups = currentGroups == null ? List.of() : List.copyOf(currentGroups);
        addedGroups = addedGroups == null ? List.of() : List.copyOf(addedGroups);
    }
}
