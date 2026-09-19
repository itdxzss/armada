package com.armada.account.model.dto;

/** 分组互存接口的dto，账号范围由服务端校验。 */
public record AccountMutualContactWork(Long tenantId, Long taskId, Long actorId) {}
