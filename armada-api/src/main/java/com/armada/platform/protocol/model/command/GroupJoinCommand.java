package com.armada.platform.protocol.model.command;

/**
 * 与具体协议实现无关的进群命令。
 *
 * @param account 执行进群的账号
 * @param inviteLinkOrCode 群邀请链接或邀请码
 * @param operationId 业务操作幂等标识
 */
public record GroupJoinCommand(
        ProtocolAccountRef account,
        String inviteLinkOrCode,
        String operationId
) {
    public GroupJoinCommand {
        if (account == null) {
            throw new IllegalArgumentException("account 不能为空");
        }
        if (inviteLinkOrCode == null || inviteLinkOrCode.isBlank()) {
            throw new IllegalArgumentException("inviteLinkOrCode 不能为空");
        }
        inviteLinkOrCode = inviteLinkOrCode.trim();
        operationId = operationId == null ? "" : operationId.trim();
    }
}
