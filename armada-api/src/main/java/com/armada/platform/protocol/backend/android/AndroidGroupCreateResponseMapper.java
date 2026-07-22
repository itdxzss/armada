package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把 Android Zhuan 建群成功响应转换为统一建群结果。
 */
public final class AndroidGroupCreateResponseMapper {

    private static final String GROUP_JID_SUFFIX = "@g.us";

    private static final String PARTICIPANTS_FIELD = "Participants";

    private final AndroidGroupMemberMapper memberMapper;

    /**
     * 创建 Android 建群响应 mapper。
     *
     * @param memberMapper Android 群成员响应 mapper
     */
    public AndroidGroupCreateResponseMapper(AndroidGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    /**
     * 解析建群成功 Data，并按请求成员补齐保守的逐成员结果。
     *
     * @param data Android 原生响应 Data
     * @param requestedParticipants 请求建群时提交的成员号码或 JID
     * @return 统一建群结果
     */
    public GroupCreateResult map(JsonNode data, List<String> requestedParticipants) {
        String rawGroupId = data == null ? null : text(data.path("GroupId"));
        if (rawGroupId == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                    "Android 建群成功响应缺少 GroupId");
        }
        String groupJid = rawGroupId.endsWith(GROUP_JID_SUFFIX)
                ? rawGroupId
                : rawGroupId + GROUP_JID_SUFFIX;
        List<GroupParticipantResult> returnedParticipants = data.path(PARTICIPANTS_FIELD).isArray()
                ? memberMapper.map(data)
                : List.of();
        Map<String, GroupParticipantResult> returned = returnedParticipants.stream()
                .filter(participant -> participant.phone() != null)
                .collect(Collectors.toMap(
                        GroupParticipantResult::phone,
                        Function.identity(),
                        (left, right) -> left));
        List<GroupCreateParticipantResult> results = requestedParticipants.stream()
                .map(WhatsappJids::userJid)
                .map(jid -> result(jid, returned.get(phoneFromJid(jid))))
                .toList();
        boolean partial = results.stream()
                .anyMatch(result -> "UNKNOWN".equals(result.status()));
        return new GroupCreateResult(groupJid, partial, results);
    }

    private static GroupCreateParticipantResult result(
            String jid,
            GroupParticipantResult returned) {
        return new GroupCreateParticipantResult(
                jid,
                returned == null ? "UNKNOWN" : "OK",
                returned == null ? null : returned.role());
    }

    private static String phoneFromJid(String jid) {
        String localPart = jid.substring(0, jid.indexOf('@'));
        int device = localPart.indexOf(':');
        return device < 0 ? localPart : localPart.substring(0, device);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }
}
