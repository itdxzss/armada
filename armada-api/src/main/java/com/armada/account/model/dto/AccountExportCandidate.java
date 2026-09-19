package com.armada.account.model.dto;

/** 在事务内读取并锁定的账号状态，不含凭据。 */
public record AccountExportCandidate(Long id, Long groupId, Long dispatchedAt,
        Integer accountState, Integer loginState, Long marketingTaskId, Long ownerUserId) { }
