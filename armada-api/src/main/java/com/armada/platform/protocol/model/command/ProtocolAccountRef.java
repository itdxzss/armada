package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 执行协议命令所需的账号引用。
 *
 * @param armadaAccountId Armada 账号主键
 * @param backend 协议后端；空值兼容为 Web
 * @param protocolAccountId Web 协议层账号标识
 * @param wsPhone Android 原生接口使用的手机号
 */
public record ProtocolAccountRef(
        Long armadaAccountId,
        ProtocolBackend backend,
        String protocolAccountId,
        String wsPhone
) {
    public ProtocolAccountRef {
        if (armadaAccountId == null) {
            throw new IllegalArgumentException("armadaAccountId 不能为空");
        }
        backend = backend == null ? ProtocolBackend.WEB : backend;
        protocolAccountId = requireText(protocolAccountId, "protocolAccountId");
        wsPhone = requireText(wsPhone, "wsPhone");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
