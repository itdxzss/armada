package com.armada.platform.protocol.config;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.account.HttpAccountLifecycleAdapter;
import com.armada.platform.protocol.http.group.HttpGroupJoinAdapter;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 协议层防腐层 Spring 配置。
 *
 * <p>当前注册协议层基础配置、共享 {@link RestClient}、{@link ProtocolHttpExecutor}
 * 与首期账号生命周期 adapter。群组、消息等 adapter 在后续小口中单独接入。</p>
 */
@Configuration
@EnableConfigurationProperties(ProtocolProperties.class)
public class ProtocolConfiguration {

    /**
     * 注册协议层共享 RestClient。
     *
     * @param properties 协议层连接配置
     * @return 配好 baseUrl、超时、JSON 头和可选 API key 的 RestClient
     */
    @Bean
    public RestClient protocolRestClient(ProtocolProperties properties) {
        // 使用 Spring 自带的简单请求工厂,便于在这里直接设置连接和读取超时。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 设置 TCP 连接建立超时,避免协议层不可达时长时间卡住业务线程。
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        // 设置响应读取超时,避免协议层已连接但迟迟不返回时无限等待。
        factory.setReadTimeout(properties.getReadTimeoutMs());

        // 创建 RestClient 构造器,后续统一写入协议层 baseUrl、请求工厂和默认 JSON 头。
        RestClient.Builder builder = RestClient.builder()
                // 设置协议层基础地址,adapter 后续只需要传相对路径。
                .baseUrl(properties.getBaseUrl())
                // 挂载带超时配置的请求工厂。
                .requestFactory(factory)
                // 声明客户端期望协议层返回 JSON。
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // 声明默认请求体按 JSON 发送。
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        // apiKey 非空时才写请求头,本地开发允许不配置鉴权。
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            // 写入协议层约定的 x-api-key 请求头。
            builder.defaultHeader(ProtocolHttpExecutor.API_KEY_HEADER, properties.getApiKey());
        }
        // 构建不可变 RestClient Bean,交给 ProtocolHttpExecutor 复用。
        return builder.build();
    }

    /**
     * 注册协议层 HTTP 执行器。
     *
     * @param protocolRestClient 协议层共享 RestClient
     * @return 协议层 HTTP 执行器
     */
    @Bean
    public ProtocolHttpExecutor protocolHttpExecutor(RestClient protocolRestClient) {
        return new ProtocolHttpExecutor(protocolRestClient);
    }

    /**
     * 注册账号生命周期协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 账号生命周期端口 HTTP 实现
     */
    @Bean
    public AccountLifecyclePort accountLifecyclePort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpAccountLifecycleAdapter(protocolHttpExecutor);
    }

    /**
     * 注册群入群协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群入群端口 HTTP 实现
     */
    @Bean
    public GroupJoinPort groupJoinPort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpGroupJoinAdapter(protocolHttpExecutor);
    }
}
