package com.armada.account.model.dto;

/**
 * 已持有终态执行行及未提交注销账本锁的释放请求。
 * @param binding 原预留的不可变身份与归属
 * @param executionStatus 锁内读取的终态执行状态
 * @param reason 触发释放原因，不含账号号码
 * @param now 释放时间（毫秒）
 */
public record CreatorReleaseRequest(CreatorDeletionBinding binding, int executionStatus,
        String reason, long now) { }
