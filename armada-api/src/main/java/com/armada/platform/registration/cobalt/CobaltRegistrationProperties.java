package com.armada.platform.registration.cobalt;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Armada 到独立 Java 25 Cobalt 服务的内部配置，默认不发起注册。 */
@ConfigurationProperties(prefix = "armada.registration.cobalt")
public final class CobaltRegistrationProperties implements InitializingBean {
    /** 等待配置上限，不是整个注册任务期限。 */ private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    /** 集成开关。 */ private boolean enabled;
    /** 受信任的服务端地址，不接受前端传入。 */ private String baseUrl = "http://cobalt-registration:8080";
    /** 内部 Bearer 凭据。 */ private String apiToken = "";
    /** 连接及连接池等待上限。 */ private Duration connectTimeout = Duration.ofSeconds(5);
    /** 响应及 socket 等待上限。 */ private Duration readTimeout = Duration.ofSeconds(10);

    /** 校验配置，只给出固定消息，不回显密钥或 URL。 */
    @Override public void afterPropertiesSet() {
        if (enabled && !hasValidToken()) throw new IllegalStateException("Cobalt 启用时必须配置有效内部密钥");
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("Cobalt 超时必须在 1 毫秒至 30 秒之间");
        }
        try {
            URI uri = URI.create(baseUrl);
            boolean scheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            if (!scheme || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalStateException("Cobalt 地址必须是受信任的 HTTP 或 HTTPS 根地址");
        }
    }

    boolean hasValidToken() {
        return apiToken != null && !apiToken.isBlank()
                && apiToken.chars().noneMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch));
    }
    private boolean validTimeout(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(MAX_TIMEOUT) <= 0;
    }
    /** @return 集成开关 */ public boolean isEnabled() { return enabled; }
    /** @param value 集成开关 */ public void setEnabled(boolean value) { enabled = value; }
    /** @return 受信任的根地址 */ public String getBaseUrl() { return baseUrl; }
    /** @param value 受信任的根地址 */ public void setBaseUrl(String value) { baseUrl = value; }
    /** @return 内部 Bearer 密钥 */ public String getApiToken() { return apiToken; }
    /** @param value 内部 Bearer 密钥 */ public void setApiToken(String value) { apiToken = value; }
    /** @return 连接等待上限 */ public Duration getConnectTimeout() { return connectTimeout; }
    /** @param value 连接等待上限 */ public void setConnectTimeout(Duration value) { connectTimeout = value; }
    /** @return 读取等待上限 */ public Duration getReadTimeout() { return readTimeout; }
    /** @param value 读取等待上限 */ public void setReadTimeout(Duration value) { readTimeout = value; }
}
