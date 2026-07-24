package com.armada.platform.auth.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理端登录、验证码和 Redis 会话配置。 */
@ConfigurationProperties(prefix = "armada.auth")
public final class AuthProperties implements InitializingBean {

    private long defaultTenantId = 1L;
    private Duration captchaTtl = Duration.ofMinutes(2);
    private Duration sessionIdleTimeout = Duration.ofMinutes(30);
    private Duration sessionMaxLifetime = Duration.ofHours(24);
    private final Redis redis = new Redis();

    @Override
    public void afterPropertiesSet() {
        if (defaultTenantId <= 0 || captchaTtl.isNegative() || captchaTtl.isZero()
                || sessionIdleTimeout.isNegative() || sessionIdleTimeout.isZero()
                || sessionMaxLifetime.compareTo(sessionIdleTimeout) < 0) {
            throw new IllegalStateException("登录认证配置不正确");
        }
        redis.validate();
    }

    public long getDefaultTenantId() { return defaultTenantId; }
    public void setDefaultTenantId(long defaultTenantId) { this.defaultTenantId = defaultTenantId; }
    public Duration getCaptchaTtl() { return captchaTtl; }
    public void setCaptchaTtl(Duration captchaTtl) { this.captchaTtl = captchaTtl; }
    public Duration getSessionIdleTimeout() { return sessionIdleTimeout; }
    public void setSessionIdleTimeout(Duration value) { this.sessionIdleTimeout = value; }
    public Duration getSessionMaxLifetime() { return sessionMaxLifetime; }
    public void setSessionMaxLifetime(Duration value) { this.sessionMaxLifetime = value; }
    public Redis getRedis() { return redis; }

    /** 登录认证专用 Redis 连接配置。 */
    public static final class Redis {
        private String address = "localhost:6379";
        private String username = "";
        private String password = "";
        private int database;
        private boolean tls;

        private void validate() {
            if (address == null || address.isBlank() || !address.contains(":")) {
                throw new IllegalStateException("认证 Redis 地址不正确");
            }
        }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public boolean isTls() { return tls; }
        public void setTls(boolean tls) { this.tls = tls; }
    }
}
