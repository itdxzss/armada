package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;

/** 补充管理员单步协议工作所需的冻结参数。 */
public record PullTaskSupplementManagerPayload(
        PullTaskSupplementManagerOperation operation,
        Accounts accounts,
        Group group,
        PullTaskExecutionLease lease,
        boolean verificationOnly) {

    /** 冻结的协议发起方和目标账号。 */
    public record Accounts(ProtocolAccountRef actor, ProtocolAccountRef target) {
    }

    /** 目标群与稳定操作标识。 */
    public record Group(String inviteLink, String groupJid, String operationId) {
    }
}
