package com.armada.account.model.vo;

/** 分组互聊接口的vo，账号范围由服务端校验。 */
public record AccountMutualChatStatsVO(long total, long pending, long submitted, long success, long failed,
        long unknown, long canceled, long mutualPairs, long oneWayPairs) {}
