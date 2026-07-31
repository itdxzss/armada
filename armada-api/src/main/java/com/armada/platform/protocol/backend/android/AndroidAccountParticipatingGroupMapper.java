package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Zhuan 现有当前群列表响应转换为 Armada 固定账号群读取模型。
 */
public final class AndroidAccountParticipatingGroupMapper {

    private static final String GROUPS_FIELD = "GroupInfos";
    private static final String COUNT_FIELD = "Count";
    private static final String GROUP_JID_FIELD = "group_id";
    private static final String GROUP_JID_SUFFIX = "@g.us";
    private static final String SUBJECT_FIELD = "subject";
    private static final String CREATOR_FIELD = "creator";
    private static final String ADDRESSING_MODE_FIELD = "addressing_mode";
    private static final String PARTICIPANTS_FIELD = "participants";
    private static final String PARTICIPANT_JID_FIELD = "jid";
    private static final String PARTICIPANT_PHONE_FIELD = "phone_number";
    private static final String CREATION_FIELD = "creation";
    private static final String ANNOUNCE_ONLY_FIELD = "announce_only";
    private static final String GROUP_MISSING_ERROR = "Android 当前群列表缺少该群";

    private final AndroidGroupMemberMapper memberMapper;

    /**
     * 创建 Android 当前参与群响应 mapper。
     *
     * @param memberMapper Android 群成员 mapper
     */
    public AndroidAccountParticipatingGroupMapper(AndroidGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    /**
     * 映射当前参与群轻量列表。
     *
     * @param data Android 原生响应 Data
     * @param wsPhone 固定账号手机号,用于识别自身管理员身份
     * @return 当前群轻量列表
     * @throws ProtocolException 顶层数量或逐群 JID 不完整时抛出
     */
    public List<AccountParticipatingGroupResult.Group> mapGroups(
            JsonNode data,
            String wsPhone) {
        String selfPhone = normalizePhone(wsPhone);
        if (selfPhone == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 当前群列表 wsPhone 无效");
        }
        return groupNodes(data).stream()
                .map(group -> mapGroup(group, selfPhone))
                .toList();
    }

    private AccountParticipatingGroupResult.Group mapGroup(
            JsonNode group,
            String selfPhone) {
        List<GroupParticipantResult> participants = memberMapper.map(group);
        String role = selfRole(participants, selfPhone);
        WhatsappJids.OwnerIdentity owner = resolveOwner(group);
        return new AccountParticipatingGroupResult.Group(
                requireGroupJid(group),
                text(group.get(SUBJECT_FIELD)),
                participants.size(),
                owner.ownerJid(),
                owner.ownerPhone(),
                owner.kind(),
                "OWNER".equals(role) || "ADMIN".equals(role),
                booleanValue(group.get(ANNOUNCE_ONLY_FIELD)),
                longValue(group.get(CREATION_FIELD)));
    }

    private static WhatsappJids.OwnerIdentity resolveOwner(JsonNode group) {
        WhatsappJids.OwnerIdentity creator = WhatsappJids.ownerIdentity(
                text(group.get(CREATOR_FIELD)),
                text(group.get(ADDRESSING_MODE_FIELD)));
        if (creator.kind() != OwnerIdentityKind.LID) {
            return creator;
        }
        JsonNode participants = group.get(PARTICIPANTS_FIELD);
        if (participants == null || !participants.isArray()) {
            return creator;
        }
        for (JsonNode participant : participants) {
            WhatsappJids.OwnerIdentity participantLid = WhatsappJids.ownerIdentity(
                    text(participant.get(PARTICIPANT_JID_FIELD)), "lid");
            if (participantLid.kind() != OwnerIdentityKind.LID
                    || !creator.ownerJid().equals(participantLid.ownerJid())) {
                continue;
            }
            WhatsappJids.OwnerIdentity participantPhone = WhatsappJids.ownerIdentity(
                    text(participant.get(PARTICIPANT_PHONE_FIELD)), "pn");
            if (participantPhone.kind() == OwnerIdentityKind.PN) {
                return participantPhone;
            }
        }
        return creator;
    }

    /**
     * 从同一当前群快照中按请求顺序生成 metadata 摘要。
     *
     * <p>Zhuan 当前接口返回仅管理员发言状态，但仍不返回 suspended 状态，
     * 未知字段保持为空，不把未知状态伪装为正常。</p>
     *
     * @param data Android 原生响应 Data
     * @param groupJids 待查询群 JID，输出保持该顺序
     * @param wsPhone 固定账号手机号
     * @return 逐群 metadata 摘要
     * @throws ProtocolException 顶层响应或请求参数不完整时抛出
     */
    public List<AccountGroupMetadataSummaryResult> mapSummaries(
            JsonNode data,
            List<String> groupJids,
            String wsPhone) {
        List<String> requested = requireGroupJids(groupJids);
        String selfPhone = normalizePhone(wsPhone);
        if (selfPhone == null) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 当前群摘要 wsPhone 无效");
        }
        Map<String, JsonNode> groupsByJid = new LinkedHashMap<>();
        for (JsonNode group : groupNodes(data)) {
            groupsByJid.putIfAbsent(requireGroupJid(group), group);
        }
        List<AccountGroupMetadataSummaryResult> summaries =
                new ArrayList<>(requested.size());
        for (String groupJid : requested) {
            JsonNode group = groupsByJid.get(groupJid);
            summaries.add(group == null
                    ? missingSummary(groupJid)
                    : summary(groupJid, group, selfPhone));
        }
        return List.copyOf(summaries);
    }

    private AccountGroupMetadataSummaryResult summary(
            String groupJid,
            JsonNode group,
            String selfPhone) {
        try {
            List<GroupParticipantResult> participants = memberMapper.map(group);
            return new AccountGroupMetadataSummaryResult(
                    groupJid,
                    true,
                    null,
                    text(group.get(SUBJECT_FIELD)),
                    participants.size(),
                    selfRole(participants, selfPhone),
                    booleanValue(group.get(ANNOUNCE_ONLY_FIELD)),
                    false);
        } catch (ProtocolException ex) {
            return new AccountGroupMetadataSummaryResult(
                    groupJid,
                    false,
                    ex.getMessage(),
                    text(group.get(SUBJECT_FIELD)),
                    null,
                    null,
                    null,
                    false);
        }
    }

    private static AccountGroupMetadataSummaryResult missingSummary(String groupJid) {
        return new AccountGroupMetadataSummaryResult(
                groupJid,
                false,
                GROUP_MISSING_ERROR,
                null,
                null,
                null,
                null,
                false);
    }

    private static String selfRole(
            List<GroupParticipantResult> participants,
            String selfPhone) {
        for (GroupParticipantResult participant : participants) {
            if (participant != null && selfPhone.equals(participant.phone())) {
                if (Boolean.TRUE.equals(participant.owner())) {
                    return "OWNER";
                }
                return Boolean.TRUE.equals(participant.admin()) ? "ADMIN" : "MEMBER";
            }
        }
        return null;
    }

    private static List<JsonNode> groupNodes(JsonNode data) {
        JsonNode count = data == null ? null : data.get(COUNT_FIELD);
        JsonNode groups = data == null ? null : data.get(GROUPS_FIELD);
        if (count == null || !count.isIntegralNumber()
                || !count.canConvertToInt() || count.intValue() < 0
                || groups == null || !groups.isArray()) {
            throw unrecognized("Android 当前群响应缺少 Count 或 GroupInfos");
        }
        if (count.intValue() != groups.size()) {
            throw unrecognized("Android 当前群响应数量不一致");
        }
        List<JsonNode> result = new ArrayList<>(groups.size());
        groups.forEach(result::add);
        return List.copyOf(result);
    }

    private static List<String> requireGroupJids(List<String> groupJids) {
        if (groupJids == null || groupJids.isEmpty()) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 当前群摘要 groupJids 不能为空");
        }
        List<String> normalized = groupJids.stream()
                .map(AndroidAccountParticipatingGroupMapper::text)
                .toList();
        if (normalized.stream().anyMatch(value -> value == null)) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 当前群摘要 groupJid 不能为空");
        }
        return normalized;
    }

    private static String requireGroupJid(JsonNode group) {
        String groupJid = group == null ? null : text(group.get(GROUP_JID_FIELD));
        if (groupJid == null) {
            throw unrecognized("Android 当前群响应缺少 group_id");
        }
        return groupJid.contains("@") ? groupJid : groupJid + GROUP_JID_SUFFIX;
    }

    private static String normalizePhone(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return null;
        }
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
        return normalized.isBlank()
                || !normalized.chars().allMatch(Character::isDigit)
                ? null
                : normalized;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : text(node.asText());
    }

    private static Boolean booleanValue(JsonNode node) {
        return node == null || node.isNull() || !node.isBoolean()
                ? null
                : node.booleanValue();
    }

    private static Long longValue(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProtocolException unrecognized(String message) {
        return new ProtocolException(
                ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                message);
    }
}
