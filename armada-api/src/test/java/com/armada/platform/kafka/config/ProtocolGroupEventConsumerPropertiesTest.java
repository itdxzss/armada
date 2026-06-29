package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * 协议群组事件 consumer properties 测试。
 */
class ProtocolGroupEventConsumerPropertiesTest {

    private final ApplicationContextRunner applicationYamlContextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new FileSystemResource("src/main/resources/application.yml"))
                            .forEach(propertySource -> context.getEnvironment()
                                    .getPropertySources()
                                    .addLast(propertySource));
                } catch (IOException ex) {
                    throw new IllegalStateException("读取 application.yml 失败", ex);
                }
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void applicationYamlExposesGroupEventConsumerDefaults() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.group-events.topic"))
                    .isTrue();
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.group-events.group-id"))
                    .isTrue();

            ProtocolGroupEventConsumerProperties properties =
                    context.getBean(ProtocolGroupEventConsumerProperties.class);
            assertThat(properties.getTopic()).isEqualTo(ProtocolGroupEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(properties.getGroupId()).isEqualTo(ProtocolGroupEventConsumerProperties.DEFAULT_GROUP_ID);
        });
    }

    @EnableConfigurationProperties(ProtocolGroupEventConsumerProperties.class)
    static class TestConfig {
    }
}
