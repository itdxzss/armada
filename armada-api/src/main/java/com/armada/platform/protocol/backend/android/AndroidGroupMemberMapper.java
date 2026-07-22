package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Android Zhuan 群成员响应转换为统一成员结果。
 */
public final class AndroidGroupMemberMapper {

    private static final String PARTICIPANTS_FIELD = "Participants";

    private static final List<String> IDENTITY_FIELDS = List.of(
            "phone", "phone_number", "phoneNumber", "jid");

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
        JsonNode participants = data == null ? null : data.path(PARTICIPANTS_FIELD);
        if (participants == null || !participants.isArray()) {
            throw unrecognized("Android 群成员响应缺少 Participants 数组");
        }
        List<GroupParticipantResult> results = new ArrayList<>();
        for (JsonNode participant : participants) {
            String phone = participantPhone(participant);
            String role = text(participant.path("type"));
            boolean owner = "superadmin".equalsIgnoreCase(role);
            boolean admin = owner || "admin".equalsIgnoreCase(role);
            results.add(new GroupParticipantResult(
                    phone == null ? null : phone + "@s.whatsapp.net",
                    phone,
                    admin,
                    owner,
                    role));
        }
        return List.copyOf(results);
    }

    private static String participantPhone(JsonNode participant) {
        for (String field : IDENTITY_FIELDS) {
            String value = text(participant.path(field));
            if (value != null) {
                String phone = normalizePhone(value);
                if (phone != null) {
                    return phone;
                }
            }
        }
        return null;
    }

    private static String normalizePhone(String value) {
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
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
        return normalized;
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
}
