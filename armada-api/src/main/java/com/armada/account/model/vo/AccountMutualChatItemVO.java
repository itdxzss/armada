package com.armada.account.model.vo;

/** 分组互聊接口的vo，账号范围由服务端校验。 */
public record AccountMutualChatItemVO(Long id, Long actorId, Long targetId, String actorPhone, String targetPhone,
        String protocolBackend, int status, int attemptNo, int direction, String protocolMessageId, String reasonCode, Long submittedAt,
        Long resultAt) {}
