package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 基于现有 Zhuan HTTP 契约的 Android 原生 client。
 */
public final class HttpAndroidNativeClient implements AndroidNativeClient {

    private static final String STATUS_URI_PREFIX = "/ws/v1/auth/status/";
    private static final String JOIN_URI_PREFIX = "/ws/v1/groups/invite/";
    private static final String MEMBERS_URI_PREFIX = "/ws/v1/groups/members/";
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

    private record JoinRequest(@JsonProperty("Code") String code) {
    }

    private record MembersRequest(@JsonProperty("group_id") String groupId) {
    }
}
