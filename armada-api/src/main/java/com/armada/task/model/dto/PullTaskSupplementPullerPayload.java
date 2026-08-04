package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** 补充拉手踩链接步骤所需的冻结参数。 */
public record PullTaskSupplementPullerPayload(
        ProtocolAccountRef target,
        Group group,
        PullTaskExecutionLease lease,
        boolean verificationOnly) {

    /** 目标群与稳定操作标识。 */
    public record Group(String inviteLink, String groupJid, String operationId) {
    }
}
