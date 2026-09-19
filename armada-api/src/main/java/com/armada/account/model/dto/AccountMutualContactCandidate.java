package com.armada.account.model.dto;

/** 分组互存接口的dto，账号范围由服务端校验。 */
public record AccountMutualContactCandidate(Long id, String wsPhone, String protocolAccountId, String protocolBackend,
        Long ownerUserId, Integer accountState, Integer loginState, Integer riskStatus, Integer muteStatus) {}
