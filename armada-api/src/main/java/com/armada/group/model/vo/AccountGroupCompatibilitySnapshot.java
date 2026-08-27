package com.armada.group.model.vo;

import java.util.List;

/** phase1 兼容群句柄与尚未对调度器可见的分类计划。 */
public record AccountGroupCompatibilitySnapshot(
        List<AccountGroupMembershipSnapshot> groups,
        GroupClassificationPlan classificationPlan) {

    public AccountGroupCompatibilitySnapshot {
        groups = groups == null ? List.of() : List.copyOf(groups);
        classificationPlan = classificationPlan == null
                ? GroupClassificationPlan.empty() : classificationPlan;
    }
}
