package com.armada.shared.util;

import java.net.URI;

/**
 * HTTP/HTTPS URL 校验工具。
 */
public final class HttpUrlValidator {

    private HttpUrlValidator() {
    }

    /** 判断字符串是否为带 host 的 http/https URL。 */
    public static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
