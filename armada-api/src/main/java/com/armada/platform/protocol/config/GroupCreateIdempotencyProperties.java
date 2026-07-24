package com.armada.platform.protocol.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 建群严格幂等 Redis 配置。 */
@ConfigurationProperties(prefix = "armada.protocol.group-create-idempotency")
public final class GroupCreateIdempotencyProperties implements InitializingBean {

    private String keyPrefix = "armada:group-create:idempotency:";
    private Duration ttl = Duration.ofDays(30);

    @Override
    public void afterPropertiesSet() {
        if (keyPrefix == null || keyPrefix.isBlank() || !keyPrefix.trim().endsWith(":")) {
            throw new IllegalStateException("Group create idempotency key prefix must end with a colon");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("Group create idempotency ttl must be positive");
        }
        keyPrefix = keyPrefix.trim();
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
