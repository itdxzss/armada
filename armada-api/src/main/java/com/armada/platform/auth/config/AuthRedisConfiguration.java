package com.armada.platform.auth.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 登录认证专用字符串模板，复用现有 Redis 连接并通过 auth 键前缀隔离数据。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthRedisConfiguration {

    @Bean("authRedisTemplate")
    public StringRedisTemplate authRedisTemplate(
            @Qualifier("androidImageRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
