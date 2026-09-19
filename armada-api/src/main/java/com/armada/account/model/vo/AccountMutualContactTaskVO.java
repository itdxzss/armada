package com.armada.account.model.vo;

/** 分组互存接口的vo，账号范围由服务端校验。 */
public record AccountMutualContactTaskVO(Long id, String leftGroupName, String rightGroupName, int leftCount,
        int rightCount, int intervalSeconds, int status, long createdAt, AccountMutualContactStatsVO stats) {}
