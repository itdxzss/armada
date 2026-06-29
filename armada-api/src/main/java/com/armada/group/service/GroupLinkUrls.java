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
     * 导入链接标准对齐 wheel:一行运营文本里提取第一个 {@code chat.whatsapp.com/<22位邀请码>}。
     *
     * <p>邀请码允许字母、数字、下划线、短横线;{@code ?} 后查询串不参与校验与落库。
     * 负向断言挡住 23 位及以上的邀请码被截断成前 22 位。</p>
     */
    private static final Pattern IMPORT_INVITE = Pattern.compile(
            "(?:https?://)?(chat\\.whatsapp\\.com)/([A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])",
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
     * 导入链接专用归一化:从一行文本中提取 wheel 口径的合法邀请链接。
     *
     * @param raw 原始导入行,可以包含序号、群名、中文冒号、尾部标点或查询串
     * @return {@code chat.whatsapp.com/<22位邀请码>}
     * @throws ImportLineException 未找到合法邀请链接时
     */
    public static String normalizeImportLine(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ImportLineException("缺少群邀请链接");
        }
        var m = IMPORT_INVITE.matcher(raw.trim());
        if (!m.find()) {
            String reason = looksLikeLinkAttempt(raw) ? "格式错误，链接长度不足" : "缺少群邀请链接";
            throw new ImportLineException(reason);
        }
        return m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2);
    }

    /**
     * 尝试归一化群邀请链接,不通过异常暴露格式错误。
     *
     * <p>结构化链接需要失败原因时使用 {@link #normalize(String)};导入文本使用
     * {@link #normalizeImportLine(String)};只需要过滤合法链接的业务使用本方法。</p>
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

    private static boolean looksLikeLinkAttempt(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("whatsapp")
                || lower.contains("chat.")
                || lower.contains("http")
                || lower.contains("wa.me");
    }
}
