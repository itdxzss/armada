package com.armada.platform.protocol.config;

import com.armada.platform.protocol.idempotency.GroupCreateIdempotencyStore;
import com.armada.platform.protocol.idempotency.RedisGroupCreateIdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 建群严格幂等 Redis 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroupCreateIdempotencyProperties.class)
public class GroupCreateIdempotencyConfiguration {

    @Bean("groupCreateIdempotencyRedisTemplate")
    public StringRedisTemplate groupCreateIdempotencyRedisTemplate(
            @Qualifier("androidImageRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public GroupCreateIdempotencyStore groupCreateIdempotencyStore(
            @Qualifier("groupCreateIdempotencyRedisTemplate") StringRedisTemplate redis,
            ObjectMapper objectMapper,
            GroupCreateIdempotencyProperties properties) {
        return new RedisGroupCreateIdempotencyStore(
                redis,
                objectMapper,
                properties.getKeyPrefix(),
                properties.getTtl());
    }
}
