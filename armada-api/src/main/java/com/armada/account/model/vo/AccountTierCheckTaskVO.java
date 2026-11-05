package com.armada.account.model.vo;
/** 批次详情与分页行共用契约。 */
public record AccountTierCheckTaskVO(Long id, String name, Integer mode, Long sourceTaskId, String sourceTaskName, Integer totalCount, Integer status, Long createdAt, Long finishedAt, java.util.Map<String, Long> tierCounts, AccountTierCheckStatsVO stats) {}
