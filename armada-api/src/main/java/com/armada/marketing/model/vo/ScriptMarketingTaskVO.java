package com.armada.marketing.model.vo;

/** 任务列表统计由发送事实聚合，不维护第二套计数。 */
public record ScriptMarketingTaskVO(Long id, String taskName, Integer status,
        Integer intervalSeconds, Long startAt, Long endAt, Long createdAt,
        long groupCount, long successCount, long failedCount, long unknownCount,
        long inFlightCount) { }
