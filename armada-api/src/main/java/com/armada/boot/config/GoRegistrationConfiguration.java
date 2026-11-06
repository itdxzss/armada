package com.armada.boot.config;

import com.armada.platform.protocol.config.ProtocolProperties;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.registration.go.GoRegistrationClient;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Go 注册客户端独立装配：POST 会触发发短信，HTTP 层不得自动重试或跟随重定向。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GoRegistrationProperties.class, ProtocolProperties.class})
public class GoRegistrationConfiguration {

    /**
     * 构建专用连接池，关闭自动重试、重定向和认证重发。
     * @param properties Go 注册配置
     * @return 随 Spring 上下文关闭的 HTTP 客户端
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient goRegistrationHttpClient(GoRegistrationProperties properties) {
        Timeout connectTimeout = Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis());
        Timeout readTimeout = Timeout.ofMilliseconds(properties.getReadTimeout().toMillis());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(GoRegistrationProperties.MAX_CONCURRENCY)
                .setMaxConnPerRoute(GoRegistrationProperties.MAX_CONCURRENCY)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(readTimeout)
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(connectTimeout)
                        .setResponseTimeout(readTimeout)
                        .setAuthenticationEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build();
    }

    /**
     * 构建 Go 注册客户端；创建时不访问 Go。注册接口与 Android 协议层同在一个 Go 服务上，直接复用其地址。
     * @param httpClient 禁重试的专用传输层
     * @param objectMapper 项目统一 JSON 解析器
     * @param protocol 协议层连接配置，取 Android 后端地址
     * @return 可被注册服务注入的客户端
     */
    @Bean
    public GoRegistrationClient goRegistrationClient(
            @Qualifier("goRegistrationHttpClient") CloseableHttpClient httpClient,
            ObjectMapper objectMapper,
            ProtocolProperties protocol) {
        RestClient restClient = RestClient.builder()
                .baseUrl(protocol.requireBackend(ProtocolBackend.ANDROID).getBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        return new GoRegistrationClient(restClient, objectMapper);
    }
}
