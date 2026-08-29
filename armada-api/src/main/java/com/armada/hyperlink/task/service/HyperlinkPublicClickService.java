package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公网短链解析与点击事实事务。 */
@Service
public class HyperlinkPublicClickService {
    private static final Pattern SHORT_CODE = Pattern.compile("[A-Za-z0-9_-]{4,24}");
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskContentMapper contentMapper;
    private final ObjectMapper objectMapper;

    public HyperlinkPublicClickService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper, HyperlinkTaskContentMapper contentMapper,
            ObjectMapper objectMapper) {
        this.recipientMapper = recipientMapper;
        this.runtimeMapper = runtimeMapper;
        this.contentMapper = contentMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RedirectOutcome visit(String shortCode, HttpServletRequest request) {
        if (shortCode == null || !SHORT_CODE.matcher(shortCode).matches()) {
            return RedirectOutcome.notFound();
        }
        Long previousTenant = TenantContext.get();
        try {
            HyperlinkTaskRecipient recipient = recipientMapper.selectByShortCodeForUpdate(shortCode);
            if (recipient == null || recipient.getTenantId() == null) {
                return RedirectOutcome.notFound();
            }
            TenantContext.set(recipient.getTenantId());
            HyperlinkTaskContent content = contentMapper.selectByTaskId(recipient.getHyperlinkTaskId());
            String targetUrl = targetUrl(content);
            if (!HttpUrlValidator.isHttpUrl(targetUrl)) {
                return RedirectOutcome.gone();
            }
            long now = System.currentTimeMillis();
            boolean firstVisit = recipient.getClickCount() == null || recipient.getClickCount() == 0;
            ClientFacts facts = clientFacts(request);
            if (recipientMapper.recordPublicVisit(recipient.getId(), firstVisit, now,
                    facts.ipAddress(), facts.userAgent(), facts.browser(), facts.os(),
                    facts.device(), facts.language(), null) != 1
                    || runtimeMapper.incrementVisitFacts(recipient.getHyperlinkTaskId(),
                    firstVisit, now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "短链访问事实更新冲突");
            }
            return RedirectOutcome.found(targetUrl);
        } finally {
            if (previousTenant == null) TenantContext.clear();
            else TenantContext.set(previousTenant);
        }
    }

    private String targetUrl(HyperlinkTaskContent content) {
        if (content == null) return null;
        if (content.getPromotionLink() != null && !content.getPromotionLink().isBlank()) {
            return content.getPromotionLink().trim();
        }
        if (content.getButtons() == null || content.getButtons().isBlank()) return null;
        try {
            List<HyperlinkButton> buttons = objectMapper.readValue(content.getButtons(),
                    new TypeReference<List<HyperlinkButton>>() { });
            return buttons.stream().sorted((left, right) -> Integer.compare(
                            left.sort() == null ? Integer.MAX_VALUE : left.sort(),
                            right.sort() == null ? Integer.MAX_VALUE : right.sort()))
                    .map(HyperlinkButton::targetValue).filter(value -> value != null && !value.isBlank())
                    .findFirst().map(String::trim).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ClientFacts clientFacts(HttpServletRequest request) {
        String userAgent = truncate(request.getHeader("User-Agent"), 512);
        String lower = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        String browser = contains(lower, "samsungbrowser") ? "Samsung Browser"
                : contains(lower, "edg/") ? "Edge"
                : contains(lower, "chrome/") ? "Chrome"
                : contains(lower, "firefox/") ? "Firefox"
                : contains(lower, "safari/") ? "Safari" : null;
        String os = contains(lower, "android") ? "Android"
                : contains(lower, "iphone", "ipad", "ios") ? "iOS"
                : contains(lower, "windows") ? "Windows"
                : contains(lower, "mac os", "macintosh") ? "macOS"
                : contains(lower, "linux") ? "Linux" : null;
        String device = contains(lower, "mobile", "android", "iphone") ? "mobile"
                : contains(lower, "ipad", "tablet") ? "tablet"
                : userAgent == null ? null : "desktop";
        String language = firstLanguage(request.getHeader("Accept-Language"));
        return new ClientFacts(ipBytes(request.getRemoteAddr()), userAgent, browser, os,
                device, language);
    }

    private boolean contains(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private String firstLanguage(String value) {
        if (value == null || value.isBlank()) return null;
        String language = value.split(",", 2)[0].trim();
        return truncate(language, 32);
    }

    private byte[] ipBytes(String value) {
        if (value == null || value.isBlank()
                || (!value.contains(":") && !value.matches("[0-9.]+"))) return null;
        try {
            byte[] bytes = InetAddress.getByName(value).getAddress();
            return bytes.length == 4 || bytes.length == 16 ? bytes : null;
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record ClientFacts(byte[] ipAddress, String userAgent, String browser,
            String os, String device, String language) { }

    public record RedirectOutcome(Status status, String targetUrl) {
        public enum Status { FOUND, NOT_FOUND, GONE }
        static RedirectOutcome found(String url) { return new RedirectOutcome(Status.FOUND, url); }
        static RedirectOutcome notFound() { return new RedirectOutcome(Status.NOT_FOUND, null); }
        static RedirectOutcome gone() { return new RedirectOutcome(Status.GONE, null); }
    }
}
