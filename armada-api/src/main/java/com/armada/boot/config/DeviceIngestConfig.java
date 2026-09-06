package com.armada.boot.config;

import com.armada.boot.security.DeviceIngestTokens;
import com.armada.boot.web.DeviceImportRequestConverter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 手机导入配置必须由运行环境提供，缺失时阻止应用启动。 */
@Configuration
public class DeviceIngestConfig implements WebMvcConfigurer {

    /** 创建服务端令牌映射，避免 Spring 配置绑定异常打印秘密值。 */
    @Bean
    public DeviceIngestTokens deviceIngestTokens(Environment environment) {
        return new DeviceIngestTokens(environment.getProperty(DeviceIngestTokens.ENVIRONMENT_VARIABLE));
    }

    /** 只为手机入参启用严格解析和流限额，其余业务响应沿用已有转换器。 */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new DeviceImportRequestConverter());
    }
}
