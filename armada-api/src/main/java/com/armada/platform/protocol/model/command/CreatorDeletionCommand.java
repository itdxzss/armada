package com.armada.platform.protocol.model.command;
/** 服务端签名注销命令；授权材料不进入普通任务参数。 */
public record CreatorDeletionCommand(long tenantId, long taskId, long executionId,
        ProtocolAccountRef creator, String identityHash, String createOperationId,
        String operationId) { }
