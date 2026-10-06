package com.armada.account.model.dto;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
/** 建群前一次性账号原子预留，绑定任务、执行行和建群操作。 */
public record CreatorReservationRequest(long tenantId, long taskId, long executionId,
        ProtocolAccountRef creator, String createOperationId, long now) { }
