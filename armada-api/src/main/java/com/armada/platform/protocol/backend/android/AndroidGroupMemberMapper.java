package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Android Zhuan 群成员响应转换为统一成员结果。
 */
public final class AndroidGroupMemberMapper {

    private static final String PARTICIPANTS_FIELD = "Participants";
    private static final String GROUP_LIST_PARTICIPANTS_FIELD = "participants";

    private static final List<String> PHONE_FIELDS = List.of(
            "phone", "phone_number", "phoneNumber");

    /**
     * 解析 Android 群成员数组并归一化号码与角色。
     *
     * <p>单个成员缺少可识别号码时保留为空身份占位，避免一个异常成员使整个群成员快照失效。</p>
     *
     * @param data Android 原生响应 Data
     * @return 不可变的统一成员列表
     * @throws ProtocolException 响应缺少成员数组时抛出
     */
    public List<GroupParticipantResult> map(JsonNode data) {
        JsonNode participants = data == null ? null : data.get(PARTICIPANTS_FIELD);
        if (participants == null && data != null) {
            participants = data.get(GROUP_LIST_PARTICIPANTS_FIELD);
        }
        if (participants == null || !participants.isArray()) {
            throw unrecognized("Android 群成员响应缺少 participants 数组");
        }
        List<GroupParticipantResult> results = new ArrayList<>();
        for (JsonNode participant : participants) {
            ParticipantIdentity identity = participantIdentity(participant);
            String role = text(participant.path("type"));
            boolean owner = "superadmin".equalsIgnoreCase(role);
            boolean admin = owner || "admin".equalsIgnoreCase(role);
            results.add(new GroupParticipantResult(
                    identity == null ? null : identity.jid(),
                    identity == null ? null : pnJid(identity),
                    identity == null ? null : identity.phone(),
                    admin,
                    owner,
                    role));
        }
        return List.copyOf(results);
    }

    /**
     * 还原成员的 PN 形式 JID。
     *
     * <p>Android 群成员的主标识多为 LID,但协议在 {@code phone} 系列字段单独给出号码;
     * 据此还原可让同一个人的 PN 与 LID 身份落在同一行成员记录上。号码缺失时留空,
     * 不得由 LID 数字反推手机号。</p>
     *
     * @param identity 已归一的成员身份
     * @return PN 形式 JID;没有可信号码来源时返回 null
     */
    private static String pnJid(ParticipantIdentity identity) {
        return identity.phone() == null ? null : WhatsappJids.userJid(identity.phone());
    }

    private static ParticipantIdentity participantIdentity(JsonNode participant) {
        ParticipantIdentity jidIdentity = identityFromJid(text(participant.path("jid")));
        if (jidIdentity != null) {
            if (jidIdentity.phone() != null) {
                return jidIdentity;
            }
            ParticipantIdentity phoneIdentity = phoneIdentity(participant);
            return new ParticipantIdentity(
                    jidIdentity.jid(), phoneIdentity == null ? null : phoneIdentity.phone());
        }
        return phoneIdentity(participant);
    }

    private static ParticipantIdentity phoneIdentity(JsonNode participant) {
        for (String field : PHONE_FIELDS) {
            String value = text(participant.path(field));
            if (value != null) {
                ParticipantIdentity identity = identityFromPhone(value);
                if (identity != null) {
                    return identity;
                }
            }
        }
        return null;
    }

    private static ParticipantIdentity identityFromPhone(String value) {
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            if (!"s.whatsapp.net".equalsIgnoreCase(normalized.substring(at + 1))) {
                return null;
            }
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()
                || !normalized.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return new ParticipantIdentity(normalized + "@s.whatsapp.net", normalized);
    }

    private static ParticipantIdentity identityFromJid(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        int at = normalized.indexOf('@');
        if (at <= 0 || normalized.indexOf('@', at + 1) >= 0) {
            return null;
        }
        String server = normalized.substring(at + 1);
        if ("s.whatsapp.net".equals(server)) {
            return identityFromPhone(normalized);
        }
        if (!"lid".equals(server)) {
            return null;
        }
        String local = normalized.substring(0, at);
        int device = local.indexOf(':');
        if (device >= 0) {
            local = local.substring(0, device);
        }
        if (local.isBlank() || !local.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return new ParticipantIdentity(local + "@lid", null);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static ProtocolException unrecognized(String message) {
        return new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                message);
    }

    private record ParticipantIdentity(String jid, String phone) {
    }
}
