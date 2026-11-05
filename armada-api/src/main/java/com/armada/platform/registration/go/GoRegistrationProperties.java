package com.armada.platform.registration.go;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 接码订单对接 Go 注册服务的配置；默认关闭，启动不会访问 Go。 */
@ConfigurationProperties(prefix = "armada.account.sms-registration")
public final class GoRegistrationProperties implements InitializingBean {

    /** 并发数上限，与 Go 的 max_concurrent 合法上限一致；Armada 并发超过 Go 额度只会得到 429。 */
    public static final int MAX_CONCURRENCY = 64;
    /** 超时配置上限；不是整个注册流程的总时限。 */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    /** 默认关闭，页面上“开始注册”按钮保持禁用。 */
    private boolean enabled;
    /** 受控 Go 注册服务地址；不接受页面传入，有未完成记录时不要修改。 */
    private String baseUrl = "";
    /** 一轮内同时办理的记录数，不得超过 Go 的 max_concurrent。 */
    private int concurrency = 32;
    /** TCP 连接及连接池等待上限。 */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** 响应读取上限，必须大于 Go 单次外部操作的 20 秒期限。 */
    private Duration readTimeout = Duration.ofSeconds(25);
    /** 按租户配置的 Go 归属：不同租户不得共用同一个 Go token。 */
    private Map<Long, Tenant> tenants = new LinkedHashMap<>();

    /** 单个租户的 Go 调用凭据与注册配置。 */
    public static final class Tenant {
        /** Go 服务端映射到该租户的 Bearer 凭据，仅由服务端环境注入，禁止进日志。 */
        private String token = "";
        /** 该租户个人版 Go 注册配置 ID；0 表示未开放个人注册。 */
        private long profileId;
        /** 商业版独立配置 ID；0 表示该租户未开放商业注册。 */
        private long businessProfileId;

        /** @return Bearer 凭据 */
        public String getToken() { return token; }
        /** @param token Bearer 凭据 */
        public void setToken(String token) { this.token = token; }
        /** @return Go 注册配置 ID */
        public long getProfileId() { return profileId; }
        /** @param profileId Go 注册配置 ID */
        public void setProfileId(long profileId) { this.profileId = profileId; }

        /** @return 商业版 Go 配置 ID */
        public long getBusinessProfileId() { return businessProfileId; }
        /** @param businessProfileId 商业版 Go 配置 ID */
        public void setBusinessProfileId(long businessProfileId) { this.businessProfileId = businessProfileId; }

        private boolean valid() {
            return token != null && !token.isBlank()
                    && token.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                    && profileId >= 0 && businessProfileId >= 0
                    && (profileId > 0 || businessProfileId > 0)
                    && (profileId == 0 || profileId != businessProfileId);
        }
    }

    /** 启动时校验；错误信息不包含任何凭据值。 */
    @Override
    public void afterPropertiesSet() {
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("Go 注册超时必须在 1 毫秒至 60 秒之间");
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException("Go 注册并发数必须在 1 至 " + MAX_CONCURRENCY + " 之间");
        }
        if (!enabled) {
            return;
        }
        if (!validBaseUrl() || tenants.isEmpty() || !tenants.values().stream().allMatch(Tenant::valid)) {
            throw new IllegalStateException("Go 注册启用时必须配置有效的服务地址，以及每个租户的凭据与配置 ID");
        }
    }

    private boolean validBaseUrl() {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0 && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }

    /** @return 指定租户的 Go 配置；未配置时为空，表示该租户不能使用自动注册 */
    public Optional<Tenant> tenant(Long tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /** @return 是否启用 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return Go 服务地址 */
    public String getBaseUrl() { return baseUrl; }
    /** @param baseUrl Go 服务地址 */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    /** @return 一轮并发数 */
    public int getConcurrency() { return concurrency; }
    /** @param concurrency 一轮并发数 */
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    /** @return 连接超时 */
    public Duration getConnectTimeout() { return connectTimeout; }
    /** @param connectTimeout 连接超时 */
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    /** @return 读取超时 */
    public Duration getReadTimeout() { return readTimeout; }
    /** @param readTimeout 读取超时 */
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    /** @return 按租户的 Go 配置 */
    public Map<Long, Tenant> getTenants() { return tenants; }
    /** @param tenants 按租户的 Go 配置 */
    public void setTenants(Map<Long, Tenant> tenants) { this.tenants = tenants; }
}
