package com.armada.platform.protocol.util;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;

/**
 * WhatsApp JID 归一化工具。
 */
public final class WhatsappJids {

    private static final String USER_JID_SUFFIX = "@s.whatsapp.net";

    private WhatsappJids() {
    }

    /**
     * 把裸手机号归一成 WhatsApp 用户 JID。
     *
     * <p>调用方可以传已经完整的 JID,也可以传带国家码的手机号。裸手机号会移除常见展示字符
     * {@code + 空格 - ( )},再追加 {@code @s.whatsapp.net}。这里不补国家码,避免把本地号误判成
     * 目标国家号码。</p>
     */
    public static String userJid(String participant) {
        String normalized = requireText(participant, "participant");
        if (normalized.contains("@")) {
            return normalized;
        }
        String digits = normalized
                .replace("+", "")
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (digits.isBlank() || !digits.chars().allMatch(Character::isDigit)) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 participant 手机号非法: " + participant);
        }
        return digits + USER_JID_SUFFIX;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 " + fieldName + " 参数缺失");
        }
        return value.trim();
    }
}
