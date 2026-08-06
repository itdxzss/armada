package com.armada.platform.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** 登录认证默认配置回归测试。 */
class AuthPropertiesTest {

    @Test
    void defaultsSessionIdleTimeoutToTwoHours() {
        AuthProperties properties = new AuthProperties();

        assertThat(properties.getSessionIdleTimeout()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void applicationConfigurationDefaultsSessionIdleTimeoutToTwoHours() throws IOException {
        try (var input = Objects.requireNonNull(
                AuthPropertiesTest.class.getResourceAsStream("/application.yml"))) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(yaml).contains("session-idle-timeout: ${AUTH_SESSION_IDLE_TIMEOUT:2h}");
        }
    }
}
