package com.armada.platform.protocol.config;

import com.armada.platform.protocol.media.AndroidImageAssetStore;
import com.armada.platform.protocol.media.RedisAndroidImageAssetStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Android 营销图片 Redis 专用装配。
 *
 * <p>Value 始终使用原始字节，连接和序列化器均与未来其他 Armada Redis 用途隔离。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AndroidImageRedisProperties.class)
public class AndroidImageRedisConfiguration {

    /**
     * 创建 Android 图片专用 Lettuce 连接工厂。
     *
     * @param properties Android 图片 Redis 配置
     * @return 支持 standalone、cluster、ACL 和 TLS 的连接工厂
     */
    @Bean("androidImageRedisConnectionFactory")
    public LettuceConnectionFactory androidImageRedisConnectionFactory(
            AndroidImageRedisProperties properties) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                LettuceClientConfiguration.builder();
        if (properties.isTls()) {
            client.useSsl();
        }
        if ("cluster".equals(properties.normalizedMode())) {
            RedisClusterConfiguration cluster =
                    new RedisClusterConfiguration(properties.addressList());
            applyAuthentication(cluster, properties);
            return new LettuceConnectionFactory(cluster, client.build());
        }
        RedisNode node = redisNode(properties.addressList().get(0));
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(node.getHost(), node.getPort());
        standalone.setDatabase(properties.getDatabase());
        applyAuthentication(standalone, properties);
        return new LettuceConnectionFactory(standalone, client.build());
    }

    /**
     * 创建字符串 Key、原始字节 Value 的图片 Redis 模板。
     *
     * @param connectionFactory Android 图片专用连接工厂
     * @return 不进行 JSON 或 JDK 序列化的 Redis 模板
     */
    @Bean("androidImageRedisTemplate")
    public RedisTemplate<String, byte[]> androidImageRedisTemplate(
            @Qualifier("androidImageRedisConnectionFactory")
            LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建 Android 图片缓存端口实现。
     *
     * @param redis Android 图片专用二进制 Redis 模板
     * @param properties Android 图片 Redis 配置
     * @return 写入原图并维护 TTL 的缓存实现
     */
    @Bean
    public AndroidImageAssetStore androidImageAssetStore(
            @Qualifier("androidImageRedisTemplate") RedisTemplate<String, byte[]> redis,
            AndroidImageRedisProperties properties) {
        return new RedisAndroidImageAssetStore(redis, properties.getKeyPrefix());
    }

    private static RedisNode redisNode(String address) {
        int separator = address.lastIndexOf(':');
        return new RedisNode(
                address.substring(0, separator),
                Integer.parseInt(address.substring(separator + 1)));
    }

    private static void applyAuthentication(
            RedisStandaloneConfiguration configuration,
            AndroidImageRedisProperties properties) {
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            configuration.setUsername(properties.getUsername().trim());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
    }

    private static void applyAuthentication(
            RedisClusterConfiguration configuration,
            AndroidImageRedisProperties properties) {
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            configuration.setUsername(properties.getUsername().trim());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
    }
}
