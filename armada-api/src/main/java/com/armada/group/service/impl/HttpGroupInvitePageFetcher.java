package com.armada.group.service.impl;

import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 基于 WhatsApp 公开邀请页的群资料抓取实现。 */
@Service
public class HttpGroupInvitePageFetcher implements GroupInvitePageFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpGroupInvitePageFetcher.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int AVATAR_MAX_LENGTH = 512;
    private static final Pattern META_TAG = Pattern.compile("<meta\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private final HttpClient httpClient;

    public HttpGroupInvitePageFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpGroupInvitePageFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public GroupInvitePageMetadata fetch(String normalizedUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(inviteUri(normalizedUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", HTML_ACCEPT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("WhatsApp 邀请页返回非 2xx normalizedUrl={} status={}",
                        normalizedUrl, response.statusCode());
                return empty(normalizedUrl);
            }
            return metadataFromHtml(normalizedUrl, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WhatsApp 邀请页抓取被中断 normalizedUrl={}", normalizedUrl);
            return empty(normalizedUrl);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("WhatsApp 邀请页抓取失败 normalizedUrl={} error={}", normalizedUrl, e.getMessage());
            return empty(normalizedUrl);
        }
    }

    static GroupInvitePageMetadata metadataFromHtml(String normalizedUrl, String html) {
        String subject = null;
        String avatarUrl = null;
        if (html != null && !html.isBlank()) {
            Matcher metaMatcher = META_TAG.matcher(html);
            while (metaMatcher.find()) {
                Map<String, String> attributes = attributes(metaMatcher.group(1));
                if (isMeta(attributes, "og:title")) {
                    subject = attributes.get("content");
                } else if (isMeta(attributes, "og:image")) {
                    avatarUrl = attributes.get("content");
                }
            }
        }
        return new GroupInvitePageMetadata(
                inviteCode(normalizedUrl),
                normalizeSubject(subject),
                normalizeAvatarUrl(avatarUrl));
    }

    private static URI inviteUri(String normalizedUrl) {
        String value = normalizedUrl == null ? "" : normalizedUrl.trim();
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return URI.create(value);
        }
        return URI.create("https://" + value);
    }

    private static GroupInvitePageMetadata empty(String normalizedUrl) {
        return new GroupInvitePageMetadata(inviteCode(normalizedUrl), null, null);
    }

    private static Map<String, String> attributes(String rawAttributes) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(rawAttributes);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = firstNonNull(matcher.group(3), matcher.group(4), matcher.group(5));
            values.put(name, htmlUnescape(value));
        }
        return values;
    }

    private static boolean isMeta(Map<String, String> attributes, String key) {
        return key.equalsIgnoreCase(attributes.get("property"))
                || key.equalsIgnoreCase(attributes.get("name"));
    }

    private static String normalizeSubject(String value) {
        String subject = trimToNull(value);
        if (subject == null
                || "WhatsApp".equalsIgnoreCase(subject)
                || "WhatsApp Group Invite".equalsIgnoreCase(subject)) {
            return null;
        }
        return truncate(subject, SUBJECT_MAX_LENGTH);
    }

    private static String normalizeAvatarUrl(String value) {
        String avatarUrl = trimToNull(value);
        if (avatarUrl == null) {
            return null;
        }
        String lower = avatarUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://pps.whatsapp.net/")) {
            return null;
        }
        return truncate(avatarUrl, AVATAR_MAX_LENGTH);
    }

    private static String inviteCode(String normalizedUrl) {
        String value = trimToNull(normalizedUrl);
        if (value == null) {
            return null;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash == value.length() - 1) {
            return null;
        }
        return value.substring(slash + 1);
    }

    private static String htmlUnescape(String value) {
        if (value == null) {
            return null;
        }
        String decoded = value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        Matcher matcher = NUMERIC_ENTITY.matcher(decoded);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(decodeNumericEntity(matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String decodeNumericEntity(String entity) {
        try {
            int radix = entity.startsWith("x") || entity.startsWith("X") ? 16 : 10;
            String digits = radix == 16 ? entity.substring(1) : entity;
            int codePoint = Integer.parseInt(digits, radix);
            if (!Character.isValidCodePoint(codePoint)) {
                return "&#" + entity + ";";
            }
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException e) {
            return "&#" + entity + ";";
        }
    }

    private static String firstNonNull(String first, String second, String third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
