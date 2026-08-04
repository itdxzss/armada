package com.armada.task.model.vo;

/** 单群一种角色资源的当前有效数、冻结计划数与实时缺口。 */
public record PullTaskStandardResourceCountVO(
        int currentCount,
        int plannedCount,
        int missingCount) {
}
