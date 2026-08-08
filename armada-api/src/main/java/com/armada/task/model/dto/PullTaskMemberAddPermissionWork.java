package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** 普通成员添加权限确认所需的冻结工作项。 */
public record PullTaskMemberAddPermissionWork(
        long tenantId,
        long executionId,
        int expectedVersion,
        String lockOwner,
        String groupJid,
        ProtocolAccountRef manager) {
}
