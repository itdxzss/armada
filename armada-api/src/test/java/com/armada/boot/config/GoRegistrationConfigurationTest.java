package com.armada.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.registration.go.GoRegistrationClient;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

/** 证明自动注册不需要任何配置：客户端直接复用 Android 协议层地址，并发与超时有可用默认值。 */
class GoRegistrationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoRegistrationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void startsWithoutAnyRegistrationConfigurationAndReusesAndroidBackendAddress() {
        runner.withPropertyValues("armada.protocol.backends.ANDROID.base-url=http://go.internal:8001")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GoRegistrationClient.class);
                    assertThat(context.getBean(GoRegistrationProperties.class).getConcurrency()).isEqualTo(32);
                });
    }

    @Test
    void concurrencyBindsFromEnvironmentAndInvalidValuesFailStartup() {
        runner.withPropertyValues("armada.protocol.backends.ANDROID.base-url=http://go.internal:8001")
                .withInitializer(context -> TestPropertyValues.of("ARMADA_ACCOUNT_SMSREGISTRATION_CONCURRENCY=16")
                        .applyTo(context.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
                .run(context -> assertThat(context.getBean(GoRegistrationProperties.class).getConcurrency()).isEqualTo(16));
        runner.withPropertyValues("armada.protocol.backends.ANDROID.base-url=http://go.internal:8001",
                "armada.account.sms-registration.concurrency=65")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingAndroidBackendAddressFailsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }
}
