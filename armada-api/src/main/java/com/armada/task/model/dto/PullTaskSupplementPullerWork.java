package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** 已预写状态、可在事务外执行的补充拉手踩链接工作项。 */
public record PullTaskSupplementPullerWork(
        Long tenantId,
        Long executionId,
        Long targetGroupAccountId,
        Long actionId,
        PullTaskSupplementPullerPayload payload) {

    public ProtocolAccountRef target() {
        return payload.target();
    }

    public GroupJoinCommand joinCommand() {
        return new GroupJoinCommand(
                target(), payload.group().inviteLink(), payload.group().operationId());
    }

    public GroupMemberListQuery memberQuery() {
        return new GroupMemberListQuery(
                target(), payload.group().groupJid(),
                payload.group().operationId() + ":verify");
    }

    public String groupJid() {
        return payload.group().groupJid();
    }

    public boolean verificationOnly() {
        return payload.verificationOnly();
    }

    public String lockOwner() {
        return payload.lease().lockOwner();
    }

    public int expectedVersion() {
        return payload.lease().expectedVersion();
    }
}
