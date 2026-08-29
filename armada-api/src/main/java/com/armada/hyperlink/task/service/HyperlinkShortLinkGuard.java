package com.armada.hyperlink.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 短链公网基址的单一解析、启用门禁与 URL 构造入口。 */
@Component
public class HyperlinkShortLinkGuard {
    private final String publicBaseUrl;

    public HyperlinkShortLinkGuard(
            @Value("${armada.hyperlink.public-base-url:}") String configuredBaseUrl) {
        this.publicBaseUrl = normalizeBaseUrl(configuredBaseUrl);
    }

    /** 启用短链的任务在写入运行状态前必须具备公网基址。 */
    public void requireConfigured(Boolean shortLinkEnabled) {
        if (Boolean.TRUE.equals(shortLinkEnabled) && publicBaseUrl == null) {
            throw unavailable("armada.hyperlink.public-base-url 未配置");
        }
    }

    /** 为已分配短码构造唯一公网 URL，同时保留派发期最后一道失败关闭防线。 */
    public String publicUrl(String shortCode) {
        requireConfigured(true);
        if (shortCode == null || shortCode.isBlank()) {
            throw unavailable("启用短链的 recipient 缺少 shortCode");
        }
        return publicBaseUrl + "/api/public/hl/" + shortCode;
    }

    private static String normalizeBaseUrl(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String normalized = configured.trim().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        boolean http = "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
        if (!http || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "armada.hyperlink.public-base-url 必须为无凭据、查询和片段的 HTTP(S) 地址");
        }
        return normalized;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.HYPERLINK_DISPATCH_GUARD_UNAVAILABLE, message);
    }
}
