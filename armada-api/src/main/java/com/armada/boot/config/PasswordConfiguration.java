package com.armada.boot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 系统用户密码编码配置。 */
@Configuration
public class PasswordConfiguration {

    /**
     * 使用带算法前缀的委托编码器；当前写入格式为 {@code {bcrypt}...}，便于未来平滑升级算法。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
