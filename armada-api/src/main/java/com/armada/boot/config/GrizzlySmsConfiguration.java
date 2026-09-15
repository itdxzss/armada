package com.armada.boot.config;

import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
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

/** 接码客户端独立装配，避免 GET 付费请求被 HTTP 层重试或携带密钥跳转。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrizzlySmsProperties.class)
public class GrizzlySmsConfiguration {

    /** 固定官方 HTTPS 地址，密钥不发送到运行时传入的任意 URL。 */
    private static final String GRIZZLY_API_BASE_URL = "https://api.grizzlysms.com";

    /**
     * 构建专用连接池，关闭 GET 自动重试、重定向和 HTTP 认证重发。
     * @param properties 服务端接码配置
     * @return 随 Spring 上下文关闭的 HTTP 客户端
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient grizzlySmsHttpClient(GrizzlySmsProperties properties) {
        Timeout connectTimeout = Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis());
        Timeout readTimeout = Timeout.ofMilliseconds(properties.getReadTimeout().toMillis());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
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
     * 构建使用固定地址、没有通用 URL 日志拦截器的接码客户端。
     * @param httpClient 禁重试的专用传输层
     * @param objectMapper 项目统一 JSON 解析器
     * @param properties 接码开关及密钥
     * @return 可被 Armada 业务服务注入的接码客户端；创建时不访问供应商
     */
    @Bean
    public GrizzlySmsClient grizzlySmsClient(
            @Qualifier("grizzlySmsHttpClient") CloseableHttpClient httpClient,
            ObjectMapper objectMapper,
            GrizzlySmsProperties properties) {
        RestClient restClient = RestClient.builder()
                .baseUrl(GRIZZLY_API_BASE_URL)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
        return new GrizzlySmsClient(restClient, objectMapper, properties);
    }
}
