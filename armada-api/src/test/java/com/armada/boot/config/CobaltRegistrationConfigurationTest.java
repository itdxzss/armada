package com.armada.boot.config;

import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认未启用的 Cobalt 配置不会阻断 Armada 启动或自动发起注册。 */
class CobaltRegistrationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CobaltRegistrationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledClientCanBeInjectedWithoutCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CobaltRegistrationClient.class).isEnabled()).isFalse();
        });
    }

    @Test
    void enablingRequiresCredentialsAndRejectsCredentialInUrl() {
        runner.withPropertyValues("armada.registration.cobalt.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("armada.registration.cobalt.base-url=http://user:password@localhost")
                .run(context -> assertThat(context).hasFailed());
    }
}
