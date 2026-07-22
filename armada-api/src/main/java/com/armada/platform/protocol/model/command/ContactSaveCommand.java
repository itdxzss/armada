package com.armada.platform.protocol.model.command;

import java.util.Objects;

/**
 * 与具体协议实现无关的联系人保存命令。
 *
 * @param account 执行联系人保存的账号协议事实
 * @param contact 联系人裸手机号或完整 WhatsApp 用户 JID
 * @param name 联系人展示名
 * @param operationId 业务操作标识
 */
public record ContactSaveCommand(
        ProtocolAccountRef account,
        String contact,
        String name,
        String operationId) {

    public ContactSaveCommand {
        account = Objects.requireNonNull(account, "account 不能为空");
        contact = requireText(contact, "contact");
        name = requireText(name, "name");
        operationId = requireText(operationId, "operationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
