package com.armada.task.model.dto;

/** 把已锁定的补充站台原子绑定到一次拉人调用。 */
public record PullTaskStationBinding(
        Scope scope,
        Expected expected) {

    /** 待绑定角色行、目标调用和更新时间。 */
    public record Scope(long roleRowId, long pullCallId, long now) {
    }

    /** 绑定前必须匹配的角色事实。 */
    public record Expected(
            int roleType,
            int sourceType,
            int availabilityStatus) {
    }
}
