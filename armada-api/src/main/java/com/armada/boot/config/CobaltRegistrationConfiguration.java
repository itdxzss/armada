package com.armada.boot.config;

import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.registration.cobalt.CobaltRegistrationProperties;
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

/** Cobalt 内部 HTTP 装配，创建 bean 时不启动注册、不连接远程服务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CobaltRegistrationProperties.class)
public class CobaltRegistrationConfiguration {
    /** @param properties 受信任的服务端配置 @return 无隐式重试和重定向的专用客户端 */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient cobaltRegistrationHttpClient(CobaltRegistrationProperties properties) {
        Timeout connect = Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis());
        Timeout read = Timeout.ofMilliseconds(properties.getReadTimeout().toMillis());
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connect)
                                .setSocketTimeout(read).build()).build())
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(connect)
                        .setResponseTimeout(read).setAuthenticationEnabled(false).build())
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().build();
    }

    /** @param httpClient 专用传输层 @param mapper 项目 JSON 配置 @param properties 内部服务配置 @return 可注入的注册能力 */
    @Bean
    public CobaltRegistrationClient cobaltRegistrationClient(
            @Qualifier("cobaltRegistrationHttpClient") CloseableHttpClient httpClient,
            ObjectMapper mapper, CobaltRegistrationProperties properties) {
        RestClient restClient = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
        return new CobaltRegistrationClient(restClient, mapper, properties);
    }
}
