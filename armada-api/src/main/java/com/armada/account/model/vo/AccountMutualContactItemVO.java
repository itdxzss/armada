package com.armada.account.model.vo;

/** 分组互存接口的vo，账号范围由服务端校验。 */
public record AccountMutualContactItemVO(Long id, Long actorId, Long targetId, String actorPhone, String targetPhone,
        String protocolBackend, int status, int attemptNo, boolean retryable, String reasonCode, Long submittedAt,
        Long resultAt) {}
