package com.armada.platform.protocol.config;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void bindsBackendSpecificHttpProperties() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.backends.WEB.base-url=https://web-protocol.internal",
                        "armada.protocol.backends.WEB.api-key=web-key",
                        "armada.protocol.backends.ANDROID.base-url=https://android-protocol.internal",
                        "armada.protocol.backends.ANDROID.api-key=android-key",
                        "armada.protocol.backends.ANDROID.connect-timeout-ms=2345",
                        "armada.protocol.backends.ANDROID.read-timeout-ms=6789")
                .run(context -> {
                    ProtocolProperties properties = context.getBean(ProtocolProperties.class);

                    ProtocolBackendHttpProperties web = properties.requireBackend(ProtocolBackend.WEB);
                    ProtocolBackendHttpProperties android = properties.requireBackend(ProtocolBackend.ANDROID);
                    assertThat(web.getBaseUrl()).isEqualTo("https://web-protocol.internal");
                    assertThat(web.getApiKey()).isEqualTo("web-key");
                    assertThat(android.getBaseUrl()).isEqualTo("https://android-protocol.internal");
                    assertThat(android.getApiKey()).isEqualTo("android-key");
                    assertThat(android.getConnectTimeoutMs()).isEqualTo(2345);
                    assertThat(android.getReadTimeoutMs()).isEqualTo(6789);
                });
    }

    @Test
    void legacyConnectionPropertiesRemainWebFallbackOnly() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.base-url=https://legacy-web.internal",
                        "armada.protocol.api-key=legacy-key")
                .run(context -> {
                    ProtocolProperties properties = context.getBean(ProtocolProperties.class);

                    assertThat(properties.requireBackend(ProtocolBackend.WEB).getBaseUrl())
                            .isEqualTo("https://legacy-web.internal");
                    assertThatThrownBy(() -> properties.requireBackend(ProtocolBackend.ANDROID))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("ANDROID");
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

    @Test
    void applicationYamlWebBackendInheritsArmadaDeploymentEnvironment() {
        applicationYamlContextRunner
                .withSystemProperties(
                        "ARMADA_PROTOCOL_BASE_URL=https://deployed-protocol.internal",
                        "ARMADA_PROTOCOL_API_KEY=deployed-api-key")
                .run(context -> {
                    ProtocolProperties properties = context.getBean(ProtocolProperties.class);
                    ProtocolBackendHttpProperties web = properties.requireBackend(ProtocolBackend.WEB);

                    assertThat(web.getBaseUrl()).isEqualTo("https://deployed-protocol.internal");
                    assertThat(web.getApiKey()).isEqualTo("deployed-api-key");
                });
    }

    @EnableConfigurationProperties(ProtocolProperties.class)
    static class TestConfig {
    }
}
