package com.armada.task.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 把 WhatsApp 群邀请链接或纯邀请码规范化为协议命令字段。
 *
 * <p>这里只接受 HTTPS 的 {@code chat.whatsapp.com/{code}} 或纯 code，拒绝查询参数、fragment 和额外
 * 路径，避免把任意 URL 传给协议层并在异步链路末端才暴露配置错误。</p>
 */
@Component
public class JoinTaskInviteCodeParser {

    /** WhatsApp 官方群邀请链接域名。 */
    private static final String INVITE_HOST = "chat.whatsapp.com";

    /** 协议层接受的邀请码字符集合。 */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * 提取并校验群邀请码。
     *
     * @param input 完整 WhatsApp 群邀请链接或纯邀请码
     * @return 去除链接结构后的邀请码
     * @throws IllegalArgumentException 输入为空、不是官方 HTTPS 链接或邀请码字符非法时抛出
     */
    public String parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("群邀请码不能为空");
        }
        String value = input.trim();
        if (!value.contains("://")) {
            return requireCode(value);
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !INVITE_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("群邀请链接格式错误");
            }
            String path = uri.getPath();
            if (path == null) {
                throw new IllegalArgumentException("群邀请链接缺少邀请码");
            }
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            if (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.contains("/")) {
                throw new IllegalArgumentException("群邀请链接路径错误");
            }
            return requireCode(normalized);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("群邀请链接格式错误", ex);
        }
    }

    /** 校验已经剥离 URL 结构的邀请码，确保不会把路径片段当作 code。 */
    private static String requireCode(String value) {
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("群邀请码格式错误");
        }
        return value;
    }
}
