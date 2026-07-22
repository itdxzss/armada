package com.armada.platform.protocol.model.command;

import java.util.List;
import java.util.Objects;

/**
 * 与具体协议实现无关的建群命令。
 *
 * @param account 执行建群的账号协议事实
 * @param subject 群名称
 * @param participants 初始成员手机号或用户 JID
 * @param announceOnly 是否请求仅管理员发言
 * @param operationId 业务操作标识
 */
public record GroupCreateCommand(
        ProtocolAccountRef account,
        String subject,
        List<String> participants,
        boolean announceOnly,
        String operationId) {

    public GroupCreateCommand {
        account = Objects.requireNonNull(account, "account 不能为空");
        subject = requireText(subject, "subject");
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("participants 不能为空");
        }
        participants = participants.stream()
                .map(value -> requireText(value, "participant"))
                .toList();
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
