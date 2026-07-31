package com.armada.platform.protocol.util;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import java.util.Locale;

/**
 * WhatsApp JID 归一化工具。
 */
public final class WhatsappJids {

    private static final String USER_JID_SUFFIX = "@s.whatsapp.net";

    private static final String LID_JID_SUFFIX = "@lid";

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

    /**
     * 根据 creator JID 与 addressing mode 归一化群主身份。
     *
     * <p>mode 与后缀冲突、后缀未知或 PN user 不是纯数字时返回 UNKNOWN。
     * 只有 PN 会输出 ownerPhone；LID 明确输出空号码，避免内部身份被当作手机号。</p>
     *
     * @param rawOwner 协议返回的 creator/owner
     * @param rawAddressingMode Android addressing mode；Web 响应可为空
     * @return 非空的三态群主身份
     */
    public static OwnerIdentity ownerIdentity(String rawOwner, String rawAddressingMode) {
        String owner = rawOwner == null ? "" : rawOwner.trim();
        if (owner.isBlank()) {
            return OwnerIdentity.unknown(null);
        }
        OwnerIdentityKind fromMode = kindFromMode(rawAddressingMode);
        OwnerIdentityKind fromSuffix = kindFromSuffix(owner);
        if (fromMode != OwnerIdentityKind.UNKNOWN
                && fromSuffix != OwnerIdentityKind.UNKNOWN
                && fromMode != fromSuffix) {
            return OwnerIdentity.unknown(owner);
        }
        if (owner.contains("@") && fromSuffix == OwnerIdentityKind.UNKNOWN) {
            return OwnerIdentity.unknown(owner);
        }
        OwnerIdentityKind kind = fromMode != OwnerIdentityKind.UNKNOWN ? fromMode : fromSuffix;
        return switch (kind) {
            case PN -> pnIdentity(owner);
            case LID -> lidIdentity(owner);
            case UNKNOWN -> OwnerIdentity.unknown(owner);
        };
    }

    private static OwnerIdentityKind kindFromMode(String rawAddressingMode) {
        if (rawAddressingMode == null || rawAddressingMode.isBlank()) {
            return OwnerIdentityKind.UNKNOWN;
        }
        return switch (rawAddressingMode.trim().toLowerCase(Locale.ROOT)) {
            case "pn" -> OwnerIdentityKind.PN;
            case "lid" -> OwnerIdentityKind.LID;
            default -> OwnerIdentityKind.UNKNOWN;
        };
    }

    private static OwnerIdentityKind kindFromSuffix(String owner) {
        String normalized = owner.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(USER_JID_SUFFIX)) {
            return OwnerIdentityKind.PN;
        }
        return normalized.endsWith(LID_JID_SUFFIX)
                ? OwnerIdentityKind.LID
                : OwnerIdentityKind.UNKNOWN;
    }

    private static OwnerIdentity pnIdentity(String owner) {
        String user = stripSuffix(owner, USER_JID_SUFFIX);
        int deviceSeparator = user.indexOf(':');
        if (deviceSeparator >= 0) {
            user = user.substring(0, deviceSeparator);
        }
        if (user.startsWith("+")) {
            user = user.substring(1);
        }
        if (user.isBlank() || !user.chars().allMatch(Character::isDigit)) {
            return OwnerIdentity.unknown(owner);
        }
        return new OwnerIdentity(
                user + USER_JID_SUFFIX,
                user,
                OwnerIdentityKind.PN);
    }

    private static OwnerIdentity lidIdentity(String owner) {
        String user = stripSuffix(owner, LID_JID_SUFFIX);
        if (user.isBlank()) {
            return OwnerIdentity.unknown(owner);
        }
        return new OwnerIdentity(user + LID_JID_SUFFIX, null, OwnerIdentityKind.LID);
    }

    private static String stripSuffix(String owner, String suffix) {
        return owner.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? owner.substring(0, owner.length() - suffix.length())
                : owner;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 " + fieldName + " 参数缺失");
        }
        return value.trim();
    }

    /**
     * 归一化后的 WhatsApp 群主身份。
     *
     * @param ownerJid 完整 JID；UNKNOWN 可保留原始值
     * @param ownerPhone 只有 PN 才有的纯数字国际手机号
     * @param kind 群主身份类型
     */
    public record OwnerIdentity(
            String ownerJid,
            String ownerPhone,
            OwnerIdentityKind kind) {

        private static OwnerIdentity unknown(String ownerJid) {
            return new OwnerIdentity(ownerJid, null, OwnerIdentityKind.UNKNOWN);
        }
    }
}
