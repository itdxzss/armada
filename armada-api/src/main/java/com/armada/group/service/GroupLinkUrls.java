package com.armada.group.service;

import com.armada.shared.util.ImportLineException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 群邀请链接归一化 + 格式校验。
 *
 * <p>归一化规则：去 scheme(https://)、host 小写、去末尾斜杠、邀请码原样保留大小写。
 * 仅接受 {@code chat.whatsapp.com/<code>} 格式；其余格式抛 {@link ImportLineException}。</p>
 */
public final class GroupLinkUrls {

    private GroupLinkUrls() {}

    /** WhatsApp 群邀请链接正则:可选 scheme + host(大小写不敏感) + 邀请码(字母数字)。 */
    private static final Pattern INVITE = Pattern.compile(
            "(?:https?://)?(chat\\.whatsapp\\.com)/([A-Za-z0-9]+)/?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 归一化群邀请链接为 {@code chat.whatsapp.com/<邀请码>}(host 小写,邀请码原样,无末尾斜杠)。
     *
     * @param raw 原始行(已 trim)
     * @return 归一化后的链接字符串
     * @throws ImportLineException 不符合 WhatsApp 群邀请链接格式时
     */
    public static String normalize(String raw) {
        var m = INVITE.matcher(raw.trim());
        if (!m.matches()) {
            throw new ImportLineException("格式错误:不是有效的 WhatsApp 群邀请链接");
        }
        return m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2);
    }

    /**
     * 尝试归一化群邀请链接,不通过异常暴露格式错误。
     *
     * <p>导入链接需要失败原因时使用 {@link #normalize(String)};只需要过滤合法链接的业务使用本方法。</p>
     *
     * @param raw 原始链接文本
     * @return 合法时返回归一化链接,非法时返回空
     */
    public static Optional<String> tryNormalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(normalize(raw));
        } catch (ImportLineException e) {
            return Optional.empty();
        }
    }
}
