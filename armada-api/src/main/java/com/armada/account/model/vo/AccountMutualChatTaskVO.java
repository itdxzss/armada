package com.armada.account.model.vo;

/** 分组互聊接口的vo，账号范围由服务端校验。 */
public record AccountMutualChatTaskVO(Long id, String leftGroupName, String rightGroupName, int leftCount,
        int rightCount, String leftText, String rightText, int intervalSeconds, int status, long createdAt, AccountMutualChatStatsVO stats) {}
