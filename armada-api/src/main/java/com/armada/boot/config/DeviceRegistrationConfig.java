package com.armada.boot.config;

import com.armada.account.controller.DeviceRegistrationController;
import com.armada.account.service.DeviceRegistrationPermitService;
import com.armada.account.service.CloudRegistrationDeviceService;
import com.armada.shared.tenant.TenantContext;
import com.armada.boot.security.DeviceRegistrationAuthenticationFilter;
import com.armada.boot.security.DeviceRegistrationTokens;
import com.armada.platform.tenant.mapper.TenantMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 手机注册默认无许可；不改变旧导入令牌和管理员权限。 */
@Configuration
public class DeviceRegistrationConfig {
    /** 将注册配置中的公开目录接入业务域；租户在每次调用时解析。 */
    @Bean public CloudRegistrationDeviceService cloudRegistrationDeviceService(DeviceRegistrationTokens tokens) {
        return () -> tokens.cloudDevices(TenantContext.get());
    }
    /** 只在进程环境注入秘密，避免配置绑定异常携带原文。 */
    @Bean public DeviceRegistrationTokens deviceRegistrationTokens(Environment environment) {
        return new DeviceRegistrationTokens(environment.getProperty(DeviceRegistrationTokens.ENVIRONMENT_VARIABLE, "[]"),
                environment.getProperty(DeviceRegistrationTokens.TEST_ENVIRONMENT_VARIABLE, "[]"));
    }
    /** 精确路由检查由独立过滤器执行，未知子路由也不得落入管理员链。 */
    @Bean @Order(0) @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain deviceRegistrationSecurityFilterChain(HttpSecurity http, DeviceRegistrationTokens tokens,
            TenantMapper tenants, DeviceRegistrationPermitService permits) throws Exception {
        http.securityMatcher(request -> request.getRequestURI().startsWith(DeviceRegistrationController.PREFIX))
                .csrf(csrf -> csrf.disable()).cors(cors -> cors.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new DeviceRegistrationAuthenticationFilter(tokens, tenants, permits), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
