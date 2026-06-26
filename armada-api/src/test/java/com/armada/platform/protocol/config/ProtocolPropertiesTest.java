package com.armada.platform.protocol.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    private final ApplicationContextRunner applicationYamlContextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new FileSystemResource("src/main/resources/application.yml"))
                            .forEach(propertySource -> context.getEnvironment()
                                    .getPropertySources()
                                    .addLast(propertySource));
                } catch (IOException e) {
                    throw new IllegalStateException("读取 application.yml 失败", e);
                }
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsProtocolConnectionProperties() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.base-url=https://protocol.internal",
                        "armada.protocol.api-key=secret-api-key",
                        "armada.protocol.connect-timeout-ms=1234",
                        "armada.protocol.read-timeout-ms=5678")
                .run(context -> {
                    ProtocolProperties properties = context.getBean(ProtocolProperties.class);

                    assertThat(properties.getBaseUrl()).isEqualTo("https://protocol.internal");
                    assertThat(properties.getApiKey()).isEqualTo("secret-api-key");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(1234);
                    assertThat(properties.getReadTimeoutMs()).isEqualTo(5678);
                });
    }

    @Test
    void providesConservativeTimeoutDefaultsAndRedactedString() {
        contextRunner.run(context -> {
            ProtocolProperties properties = context.getBean(ProtocolProperties.class);

            assertThat(properties.getConnectTimeoutMs()).isEqualTo(3_000);
            assertThat(properties.getReadTimeoutMs()).isEqualTo(60_000);

            properties.setBaseUrl("https://protocol.internal");
            properties.setApiKey("secret-api-key");
            assertThat(properties.toString())
                    .doesNotContain("https://protocol.internal")
                    .doesNotContain("secret-api-key");
        });
    }

    @Test
    void bindsApplicationYamlProtocolDefaults() {
        applicationYamlContextRunner.run(context -> {
            ProtocolProperties properties = context.getBean(ProtocolProperties.class);

            assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:3000");
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getConnectTimeoutMs()).isEqualTo(3_000);
            assertThat(properties.getReadTimeoutMs()).isEqualTo(60_000);
        });
    }

    @EnableConfigurationProperties(ProtocolProperties.class)
    static class TestConfig {
    }
}
