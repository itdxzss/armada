package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Android 原生进群输入和成功响应。
 */
public final class AndroidGroupJoinResponseMapper {

    private static final String HTTPS_SCHEME = "https";
    private static final String INVITE_HOST = "chat.whatsapp.com";
    private static final String GROUP_JID_SUFFIX = "@g.us";
    private static final Pattern INVITE_CODE_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("群聊ID:\\s*([0-9-]+(?:@g\\.us)?)");

    /**
     * 从纯邀请码或严格 WhatsApp HTTPS 邀请链接中提取邀请码。
     *
     * <p>完整链接不得包含 userinfo、端口、query、fragment 或额外路径；纯邀请码仅接受
     * URL-safe 的字母、数字、下划线和连字符。</p>
     *
     * @param inviteLinkOrCode WhatsApp 邀请链接或纯邀请码
     * @return 去除首尾空白后的纯邀请码
     * @throws ProtocolException 输入不是受支持的邀请链接或邀请码时抛出
     */
    public String inviteCode(String inviteLinkOrCode) {
        String value = requireText(inviteLinkOrCode);
        if (!value.contains("://")) {
            return requireInviteCode(value);
        }
        try {
            URI uri = URI.create(value);
            if (!HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme())
                    || !INVITE_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw invalidLink();
            }
            String path = uri.getRawPath();
            if (path == null || path.length() <= 1 || path.substring(1).contains("/")) {
                throw invalidLink();
            }
            return requireInviteCode(path.substring(1));
        } catch (IllegalArgumentException ex) {
            throw invalidLink();
        }
    }

    /**
     * 从 Android 原生成功文本中提取并归一化群 JID。
     *
     * @param data Android 原生响应 Data
     * @return 带 {@code @g.us} 后缀的群 JID
     * @throws ProtocolException Data 中不存在可识别群 ID 时抛出
     */
    public String groupJid(JsonNode data) {
        String text = data == null || data.isNull() ? "" : data.asText("");
        Matcher matcher = GROUP_ID_PATTERN.matcher(text);
        if (!matcher.find()) {
            throw new ProtocolException(
                    ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED,
                    "Android 进群成功响应缺少群 ID");
        }
        String groupId = matcher.group(1);
        return groupId.endsWith(GROUP_JID_SUFFIX)
                ? groupId
                : groupId + GROUP_JID_SUFFIX;
    }

    private static String requireInviteCode(String value) {
        if (!INVITE_CODE_PATTERN.matcher(value).matches()) {
            throw invalidLink();
        }
        return value;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalidLink();
        }
        return value.trim();
    }

    private static ProtocolException invalidLink() {
        return new ProtocolException(
                ProtocolErrorCode.INVALID_GROUP_LINK,
                "WhatsApp 群邀请链接非法");
    }
}
