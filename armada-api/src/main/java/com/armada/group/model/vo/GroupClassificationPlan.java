package com.armada.group.model.vo;

import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** phase1 已固化的群分类及其中由本事务首次提升的子集。 */
public record GroupClassificationPlan(
        Map<Long, GroupMetadataSyncTrigger> desired,
        Map<Long, GroupMetadataSyncTrigger> newlyPersisted) {

    public GroupClassificationPlan {
        desired = stable(desired);
        newlyPersisted = stable(newlyPersisted);
    }

    /** 无分类写入。 */
    public static GroupClassificationPlan empty() {
        return new GroupClassificationPlan(Map.of(), Map.of());
    }

    /** 合并同一事件内的分类；历史群触发优先于上控后触发。 */
    public GroupClassificationPlan merge(GroupClassificationPlan other) {
        if (other == null) {
            return this;
        }
        return new GroupClassificationPlan(
                merge(desired, other.desired),
                merge(newlyPersisted, other.newlyPersisted));
    }

    /** 已固化但非本事务新写的分类，只允许补缺失或延期任务。 */
    public Map<Long, GroupMetadataSyncTrigger> recoveryOnly() {
        Map<Long, GroupMetadataSyncTrigger> recovery = new TreeMap<>(desired);
        newlyPersisted.keySet().forEach(recovery::remove);
        return Collections.unmodifiableMap(recovery);
    }

    private static Map<Long, GroupMetadataSyncTrigger> merge(
            Map<Long, GroupMetadataSyncTrigger> left,
            Map<Long, GroupMetadataSyncTrigger> right) {
        Map<Long, GroupMetadataSyncTrigger> merged = new TreeMap<>();
        stable(left).forEach(merged::put);
        stable(right).forEach((groupLinkId, trigger) ->
                merged.merge(groupLinkId, trigger, GroupClassificationPlan::preferred));
        return Collections.unmodifiableMap(merged);
    }

    private static GroupMetadataSyncTrigger preferred(
            GroupMetadataSyncTrigger left,
            GroupMetadataSyncTrigger right) {
        if (left == GroupMetadataSyncTrigger.BASELINE_CAPTURED
                || right == GroupMetadataSyncTrigger.BASELINE_CAPTURED) {
            return GroupMetadataSyncTrigger.BASELINE_CAPTURED;
        }
        return left;
    }

    private static Map<Long, GroupMetadataSyncTrigger> stable(
            Map<Long, GroupMetadataSyncTrigger> source) {
        Map<Long, GroupMetadataSyncTrigger> stable = new TreeMap<>();
        if (source != null) {
            source.forEach((groupLinkId, trigger) -> {
                if (groupLinkId != null && trigger != null) {
                    stable.put(groupLinkId, trigger);
                }
            });
        }
        return Collections.unmodifiableMap(stable);
    }
}
