package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.routing.FixedAccountGroupMetadataBackend;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 复用 Zhuan 群成员接口的 Android 固定账号只读群 metadata backend。
 */
public final class AndroidNativeFixedAccountGroupMetadataAdapter
        implements FixedAccountGroupMetadataBackend {

    private static final String OPERATION = "group.metadata.get";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;
    private final AndroidGroupMemberMapper memberMapper;

    /**
     * 创建 Android 固定账号只读群 metadata adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param memberMapper Android 群成员响应 mapper
     */
    public AndroidNativeFixedAccountGroupMetadataAdapter(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupMemberMapper memberMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 读取 Android 群名称、发言和成员添加权限，并把 Zhuan 未提供的设置状态保持为未知。
     *
     * @param account 固定操作账号引用
     * @param groupJid 群 JID
     * @return 只读群详情
     */
    @Override
    public GroupMetadataResult getMetadata(
            ProtocolAccountRef account,
            String groupJid) {
        String jid = requireText(groupJid, "groupJid");
        String operationId = "armada-account:" + account.armadaAccountId();
        try {
            AndroidDecodedResponse response = decoder.decode(
                    client.members(account.wsPhone(), jid));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        account,
                        OPERATION,
                        operationId);
            }
            return map(response.data(), jid);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    OPERATION,
                    operationId);
        }
    }

    private GroupMetadataResult map(JsonNode data, String requestedGroupJid) {
        if (data == null || !data.isObject()) {
            throw unrecognized("Android 群成员响应 Data 无效");
        }
        String normalizedRequestedGroupJid = normalizeGroupJid(requestedGroupJid);
        String responseGroupJid = normalizeGroupJid(text(data.get("GroupId")));
        if (!normalizedRequestedGroupJid.equals(responseGroupJid)) {
            throw unrecognized("Android 群成员响应 GroupId 与请求不一致");
        }
        List<GroupParticipantResult> participants = memberMapper.map(data);
        JsonNode count = data.get("Count");
        if (count == null || !count.isIntegralNumber()
                || !count.canConvertToInt()
                || count.intValue() != participants.size()) {
            throw unrecognized("Android 群成员响应 Count 与 Participants 不一致");
        }
        Boolean inviteViaLink = memberLinkMode(data);
        return new GroupMetadataResult(
                responseGroupJid,
                text(data.get("Subject")),
                text(data.get("Desc")),
                text(data.get("Owner")),
                positiveLong(data.get("Creation")),
                true,
                booleanValue(data.get("Announce")),
                booleanValue(data.get("Locked"), "Locked"),
                memberAddMode(data),
                joinApprovalMode(data),
                null,
                inviteViaLink,
                inviteViaLink != null,
                inviteViaLink == null
                        ? "Android metadata 未返回 member_link_mode"
                        : null,
                false,
                true,
                participants);
    }

    /**
     * 把 Android 的进群审核状态映射为统一布尔权限。
     *
     * <p>Go 侧把 {@code <membership_approval_mode><group_join state=.../>} 解析成
     * {@code group_join_state} 一并返回；不接这个字段会让群详情页的回读确认恒拿到 null，
     * 从而把成功的设置误判成状态不一致。字段缺失时返回 null 表示未观察到。</p>
     */
    private static Boolean joinApprovalMode(JsonNode data) {
        JsonNode node = data.get("GroupJoinState");
        if (node == null) {
            node = data.get("group_join_state");
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "on" -> true;
            case "off" -> false;
            default -> null;
        };
    }

    private static Boolean memberAddMode(JsonNode data) {
        JsonNode node = data.get("MemberAddMode");
        if (node == null) {
            node = data.get("member_add_mode");
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "all_member_add" -> true;
            case "admin_add" -> false;
            default -> throw unrecognized("Android 群成员响应 MemberAddMode 无效");
        };
    }

    private static Boolean memberLinkMode(JsonNode data) {
        JsonNode node = data.get("MemberLinkMode");
        if (node == null) {
            node = data.get("member_link_mode");
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "all_member_link" -> true;
            case "admin_link" -> false;
            default -> throw unrecognized("Android 群成员响应 MemberLinkMode 无效");
        };
    }

    private static Boolean booleanValue(JsonNode node) {
        return booleanValue(node, "Announce");
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.intValue() != 0;
        }
        String value = node.asText("").trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw unrecognized("Android 群成员响应 " + field + " 无效");
    }

    private static Long positiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Long value;
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            value = node.longValue();
        } else if (node.isTextual()) {
            try {
                value = Long.valueOf(node.textValue().trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        } else {
            return null;
        }
        return value > 0 ? value : null;
    }

    private static String normalizeGroupJid(String value) {
        String normalized = textValue(value);
        if (normalized == null || normalized.contains("@")) {
            return normalized;
        }
        return normalized + "@g.us";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 群详情 " + field + " 不能为空");
        }
        return value.trim();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String textValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static ProtocolException unrecognized(String message) {
        return new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                message);
    }
}
