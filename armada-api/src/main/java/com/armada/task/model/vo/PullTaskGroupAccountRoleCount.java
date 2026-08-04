package com.armada.task.model.vo;

/**
 * 执行行内按角色统计的当前可用账号数。
 *
 * <p>详情页的"当前可用拉手数 / 计划拉手数"由本投影现算，执行行上不存资源快照列——
 * 快照列要在六个写路径上同步计数器，且随时可能与明细不一致。</p>
 */
public class PullTaskGroupAccountRoleCount {

    /** 角色，取值见 PullTaskGroupAccountRole。 */
    private Integer roleType;

    /** 该角色当前可用账号数。 */
    private Integer availableCount;

    public Integer getRoleType() {
        return roleType;
    }

    public void setRoleType(Integer roleType) {
        this.roleType = roleType;
    }

    public Integer getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(Integer availableCount) {
        this.availableCount = availableCount;
    }
}
