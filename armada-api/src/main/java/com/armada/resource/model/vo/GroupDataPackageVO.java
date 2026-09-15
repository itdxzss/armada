package com.armada.resource.model.vo;

/** 拉群数据包接口VO。 */
public record GroupDataPackageVO(Long id, String name, String remark, Integer generation, Integer version, String primaryCountryIso2, String continent, java.util.List<String> usageBusinesses, GroupDataPackageMetricsVO metrics, Long createdAt, Long updatedAt) { }
