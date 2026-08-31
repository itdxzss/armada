package com.armada.feed.task.model.vo;

import java.util.Map;

/** 动态发布任务列表与详情视图。 */
public record FeedTaskVO(
        Long id,
        String name,
        Map<String, Object> accountFilter,
        String title,
        String description,
        String content,
        String promotionLink,
        String linkPreviewImage,
        String textColor,
        String backgroundColor,
        Integer concurrency,
        Integer retryMax,
        String startMode,
        String taskMode,
        Integer taskStatus,
        Integer status,
        Integer taskDelayMinutes,
        Integer totalAccountNum,
        Integer successAccountNum,
        Integer failedAccountNum,
        String taskStartAt,
        String taskPlannedEndAt,
        String createdAt) {
}
