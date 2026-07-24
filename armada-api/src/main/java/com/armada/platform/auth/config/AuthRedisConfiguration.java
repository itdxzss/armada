package com.armada.platform.auth.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 登录认证专用 Redis 连接，避免与图片二进制序列化配置混用。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthRedisConfiguration {

    @Bean("authRedisConnectionFactory")
    public LettuceConnectionFactory authRedisConnectionFactory(AuthProperties properties) {
        AuthProperties.Redis redis = properties.getRedis();
        String address = redis.getAddress().split(",")[0].trim();
        int separator = address.lastIndexOf(':');
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                address.substring(0, separator),
                Integer.parseInt(address.substring(separator + 1)));
        standalone.setDatabase(redis.getDatabase());
        if (redis.getUsername() != null && !redis.getUsername().isBlank()) {
            standalone.setUsername(redis.getUsername().trim());
        }
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            standalone.setPassword(RedisPassword.of(redis.getPassword()));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                LettuceClientConfiguration.builder();
        if (redis.isTls()) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(standalone, client.build());
    }

    @Bean("authRedisTemplate")
    public StringRedisTemplate authRedisTemplate(
            @Qualifier("authRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
