package com.armada.account.model.dto;

/** 分组互聊接口的dto，账号范围由服务端校验。 */
public record AccountMutualChatWork(Long tenantId, Long taskId, Long actorId) {}
