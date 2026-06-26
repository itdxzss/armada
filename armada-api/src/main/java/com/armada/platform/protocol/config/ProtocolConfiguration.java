package com.armada.platform.protocol.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 协议层防腐层 Spring 配置。
 *
 * <p>当前只注册 {@link ProtocolProperties} 配置绑定。HTTP 客户端、执行器和错误映射
 * 在后续 adapter 小口中单独接入。</p>
 */
@Configuration
@EnableConfigurationProperties(ProtocolProperties.class)
public class ProtocolConfiguration {
}
