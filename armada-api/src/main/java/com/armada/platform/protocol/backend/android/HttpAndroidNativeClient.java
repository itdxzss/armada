package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 基于现有 Zhuan HTTP 契约的 Android 原生 client。
 */
public final class HttpAndroidNativeClient implements AndroidNativeClient {

    private static final String STATUS_URI_PREFIX = "/ws/v1/auth/status/";
    private static final String JOIN_URI_PREFIX = "/ws/v1/groups/invite/";
    private static final String MEMBERS_URI_PREFIX = "/ws/v1/groups/members/";
    private static final String CONTACTS_ADD_URI_PREFIX = "/ws/v1/contacts/add/";
    private static final String GROUP_CREATE_URI_PREFIX = "/ws/v1/groups/create/";
    private static final String GROUP_ANNOUNCEMENT_URI_PREFIX =
            "/ws/v1/groups/settings/sendmessage/";
    private static final String WS_PHONE_FIELD = "wsPhone";
    private static final String INVITE_CODE_FIELD = "inviteCode";
    private static final String GROUP_JID_FIELD = "groupJid";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建 Android 原生 HTTP client。
     *
     * @param httpExecutor 已绑定 Android baseUrl、超时与鉴权配置的执行器
     */
    public HttpAndroidNativeClient(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 调用 Android 原生账号状态接口。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope status(String wsPhone) {
        return httpExecutor.getTyped(
                STATUS_URI_PREFIX + requireDigits(wsPhone),
                AndroidResponseEnvelope.class);
    }

    /**
     * 按 Zhuan 原生大写 {@code Code} 字段发送进群请求。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param inviteCode WhatsApp 群邀请码
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope join(String wsPhone, String inviteCode) {
        return httpExecutor.postTyped(
                JOIN_URI_PREFIX + requireDigits(wsPhone),
                new JoinRequest(requireText(inviteCode, INVITE_CODE_FIELD)),
                AndroidResponseEnvelope.class);
    }

    /**
     * 按 Zhuan 原生 {@code group_id} 字段发送群成员查询。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param groupJid 带 {@code @g.us} 的 WhatsApp 群 JID
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope members(String wsPhone, String groupJid) {
        return httpExecutor.postTyped(
                MEMBERS_URI_PREFIX + requireDigits(wsPhone),
                new MembersRequest(requireText(groupJid, GROUP_JID_FIELD)),
                AndroidResponseEnvelope.class);
    }

    /**
     * 按 Zhuan 原生大写 {@code Numbers} 字段保存联系人。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param numbers 待保存的联系人号码
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope saveContacts(String wsPhone, List<String> numbers) {
        return httpExecutor.postTyped(
                CONTACTS_ADD_URI_PREFIX + requireDigits(wsPhone),
                new ContactsRequest(requireTexts(numbers, "numbers")),
                AndroidResponseEnvelope.class);
    }

    /**
     * 按 Zhuan 原生建群请求结构创建群组。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param subject 群名称
     * @param participants 初始成员 JID
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope createGroup(
            String wsPhone,
            String subject,
            List<String> participants) {
        return httpExecutor.postTyped(
                GROUP_CREATE_URI_PREFIX + requireDigits(wsPhone),
                new CreateGroupRequest(
                        requireText(subject, "subject"),
                        requireTexts(participants, "participants")),
                AndroidResponseEnvelope.class);
    }

    /**
     * 按 Zhuan 原生 {@code group_id/state} 请求结构设置群发言权限。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param groupJid 带 {@code @g.us} 的 WhatsApp 群 JID
     * @param membersCanSend 普通成员是否可以发言
     * @return Android 原生响应包
     */
    @Override
    public AndroidResponseEnvelope setGroupAnnouncement(
            String wsPhone,
            String groupJid,
            boolean membersCanSend) {
        return httpExecutor.postTyped(
                GROUP_ANNOUNCEMENT_URI_PREFIX + requireDigits(wsPhone),
                new AnnouncementRequest(
                        requireText(groupJid, GROUP_JID_FIELD),
                        membersCanSend),
                AndroidResponseEnvelope.class);
    }

    private static String requireDigits(String value) {
        String normalized = requireText(value, WS_PHONE_FIELD);
        if (!normalized.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("wsPhone 必须为纯数字");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static List<String> requireTexts(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return values.stream()
                .map(value -> requireText(value, field + " item"))
                .toList();
    }

    private record JoinRequest(@JsonProperty("Code") String code) {
    }

    private record MembersRequest(@JsonProperty("group_id") String groupId) {
    }

    private record ContactsRequest(@JsonProperty("Numbers") List<String> numbers) {
    }

    private record CreateGroupRequest(String subject, List<String> participants) {
    }

    private record AnnouncementRequest(
            @JsonProperty("group_id") String groupId,
            @JsonProperty("state") boolean membersCanSend) {
    }
}
