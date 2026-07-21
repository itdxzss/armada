package com.armada.platform.protocol.model.command;

import java.util.Objects;

/**
 * 与具体协议实现无关的群成员列表查询。
 *
 * @param account 执行查询的账号协议事实
 * @param groupJid WhatsApp 群 JID
 * @param operationId 业务操作标识
 */
public record GroupMemberListQuery(
        ProtocolAccountRef account,
        String groupJid,
        String operationId) {

    public GroupMemberListQuery {
        account = Objects.requireNonNull(account, "account 不能为空");
        groupJid = requireText(groupJid, "groupJid");
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
