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
    private static final long LEGACY_WEB_ACCOUNT_ID = 0L;

    public ProtocolAccountRef {
        if (armadaAccountId == null) {
            throw new IllegalArgumentException("armadaAccountId 不能为空");
        }
        backend = backend == null ? ProtocolBackend.WEB : backend;
        protocolAccountId = requireText(protocolAccountId, "protocolAccountId");
        wsPhone = requireText(wsPhone, "wsPhone");
    }

    /**
     * 兼容尚未持有 Armada 账号主键和 Android 手机号的存量 Web 调用。
     *
     * <p>新增业务不得使用此方法，应从账号表构造完整引用。</p>
     */
    public static ProtocolAccountRef legacyWeb(String protocolAccountId) {
        return new ProtocolAccountRef(
                LEGACY_WEB_ACCOUNT_ID,
                ProtocolBackend.WEB,
                protocolAccountId,
                protocolAccountId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
