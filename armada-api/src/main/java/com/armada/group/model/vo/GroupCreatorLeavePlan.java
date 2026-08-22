package com.armada.group.model.vo;

import com.armada.group.model.enums.GroupCreatorLeaveStatus;

/** 基于本地群成员投影生成的群主退群计划。 */
public record GroupCreatorLeavePlan(
        GroupCreatorLeaveAccount owner,
        GroupCreatorLeaveAccount memberToPromote,
        GroupCreatorLeaveStatus failure) {

    /** 是否满足退群前置条件。 */
    public boolean executable() {
        return failure == null;
    }

    /** 是否需要先把普通控端成员提升为管理员。 */
    public boolean promotionRequired() {
        return memberToPromote != null;
    }

    /** 构造不可执行计划。 */
    public static GroupCreatorLeavePlan failed(GroupCreatorLeaveStatus failure) {
        return new GroupCreatorLeavePlan(null, null, failure);
    }
}
