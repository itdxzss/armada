package com.armada.platform.kafka.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolCommandPublisherPropertiesTest {

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
    void bindsCommandPublisherSendTimeout() {
        contextRunner
                .withPropertyValues("armada.protocol.command-publisher.send-timeout-ms=1234")
                .run(context -> {
                    ProtocolCommandPublisherProperties properties =
                            context.getBean(ProtocolCommandPublisherProperties.class);

                    assertThat(properties.getSendTimeoutMs()).isEqualTo(1234L);
                });
    }

    @Test
    void providesDefaultSendTimeout() {
        contextRunner.run(context -> {
            ProtocolCommandPublisherProperties properties =
                    context.getBean(ProtocolCommandPublisherProperties.class);

            assertThat(properties.getSendTimeoutMs())
                    .isEqualTo(ProtocolCommandPublisherProperties.DEFAULT_SEND_TIMEOUT_MS);
        });
    }

    @Test
    void bindsApplicationYamlCommandPublisherDefaults() {
        applicationYamlContextRunner.run(context -> {
            ProtocolCommandPublisherProperties properties =
                    context.getBean(ProtocolCommandPublisherProperties.class);

            assertThat(properties.getSendTimeoutMs()).isEqualTo(10_000L);
        });
    }

    @Test
    void applicationYamlDisablesSpringJsonTypeHeaders() {
        applicationYamlContextRunner.run(context -> assertThat(context.getEnvironment()
                .getProperty("spring.kafka.producer.properties.spring.json.add.type.headers", Boolean.class))
                .isFalse());
    }

    @EnableConfigurationProperties(ProtocolCommandPublisherProperties.class)
    static class TestConfig {
    }
}
