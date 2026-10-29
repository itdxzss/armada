package com.armada.boot.config;

import com.armada.platform.wbpro.HttpWbproIntegrationClient;
import com.armada.platform.wbpro.WbproIntegrationClient;
import com.armada.platform.wbpro.WbproProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** WB 集成接口装配；创建 bean 时校验目标配置，不连接远程服务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WbproProperties.class)
public class WbproConfiguration {

    /**
     * 注册 WB 集成客户端：不跟随重定向，配置有误时启动失败。
     *
     * @param properties WB 目标配置
     * @param mapper 项目 JSON 配置
     * @return WB 集成客户端
     */
    @Bean
    public WbproIntegrationClient wbproIntegrationClient(WbproProperties properties, ObjectMapper mapper) {
        properties.parsedTargets();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new HttpWbproIntegrationClient(http, mapper, properties.getTimeout());
    }
}
