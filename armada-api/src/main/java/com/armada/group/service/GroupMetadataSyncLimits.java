package com.armada.group.service;

/** 数据库领取任务使用的租户与账号并发上限。 */
public record GroupMetadataSyncLimits(int tenantConcurrency, int accountConcurrency) {
}
