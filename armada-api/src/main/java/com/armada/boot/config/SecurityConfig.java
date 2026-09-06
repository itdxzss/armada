package com.armada.boot.config;

import com.armada.account.controller.DeviceImportController;
import com.armada.boot.security.DeviceIngestAuthenticationFilter;
import com.armada.boot.security.DeviceIngestTokens;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.boot.security.JsonAccessDeniedHandler;
import com.armada.boot.security.JsonAuthenticationEntryPoint;
import com.armada.boot.security.TokenAuthenticationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 管理端无状态 Bearer Token 安全链。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 精确设备路径只走静态令牌链，不加载 Bearer 会话或管理员权限。 */
    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain deviceIngestSecurityFilterChain(HttpSecurity http, DeviceIngestTokens tokens,
                                                               TenantMapper tenants) throws Exception {
        http.securityMatcher(request -> DeviceImportController.PATH.equals(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new DeviceIngestAuthenticationFilter(tokens, tenants),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 公开推广与登录接口保持免认证，其余 API 默认必须登录。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationFilter tokenFilter,
            JsonAuthenticationEntryPoint entryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
