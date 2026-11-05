package com.armada.account.model.dto;
/** 只读选取/身份校验快照，不包含密钥。 */
public record AccountTierCheckCandidate(Long id, String wsPhone, String protocolAccountId,
        String protocolBackend, Long ownerUserId, Integer loginState, Integer accountType,
        Long credentialVersion) {}
