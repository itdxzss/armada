package com.armada.boot.config;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.tenant.TenantContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP 横切配置:把租户上下文拦截器挂到所有业务接口 /api/**,放行公开接口 /api/public/**(登录)。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantCodeResolver tenantCodeResolver;

    public WebMvcConfig(TenantCodeResolver tenantCodeResolver) {
        this.tenantCodeResolver = tenantCodeResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantContextInterceptor(tenantCodeResolver))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**");
    }
}
