package com.armada.feed.task.model.vo;

/** 动态发布任务账号发送数据。 */
public record FeedTaskAccountVO(
        Long id,
        Long accountId,
        String accountPhone,
        String sendStatus,
        Integer retryNum,
        Integer retryMax,
        String sendAt,
        String successAt,
        String failedAt,
        String failCode,
        String failReason) {
}
