package com.armada.account.model.dto;
/** 注销全生命周期不可变业务归属；身份哈希为建群时号码的 SHA-256。 */
public record CreatorDeletionBinding(long tenantId, long taskId, long executionId,
        long accountId, String identityHash, String createOperationId, String operationId) { }
