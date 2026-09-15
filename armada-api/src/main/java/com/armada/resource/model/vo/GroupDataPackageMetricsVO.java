package com.armada.resource.model.vo;

/** 拉群数据包接口MetricsVO。 */
public record GroupDataPackageMetricsVO(long totalCount, long unusedCount, long claimedCount, long successCount, long failedCount, long privacyRejectedCount, long unregisteredCount, long unknownCount) { }
