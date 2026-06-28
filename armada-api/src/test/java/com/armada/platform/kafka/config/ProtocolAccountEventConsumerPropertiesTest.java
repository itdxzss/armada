package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * 协议账号事件 consumer properties 测试。
 */
class ProtocolAccountEventConsumerPropertiesTest {

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
    void applicationYamlExposesAccountEventConsumerDefaults() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-events.topic"))
                    .isTrue();
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-events.group-id"))
                    .isTrue();
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-events.retry-interval-ms"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-events.max-retry-attempts"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-events.dead-letter-topic-suffix"))
                    .isTrue();

            ProtocolAccountEventConsumerProperties properties =
                    context.getBean(ProtocolAccountEventConsumerProperties.class);
            assertThat(properties.getTopic()).isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(properties.getGroupId()).isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_GROUP_ID);
            assertThat(properties.getRetryIntervalMs())
                    .isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_RETRY_INTERVAL_MS);
            assertThat(properties.getMaxRetryAttempts())
                    .isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_MAX_RETRY_ATTEMPTS);
            assertThat(properties.getDeadLetterTopicSuffix())
                    .isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_DEAD_LETTER_TOPIC_SUFFIX);
        });
    }

    @Test
    void applicationYamlConfiguresKafkaStringConsumer() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("spring.kafka.consumer.key-deserializer"))
                    .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
            assertThat(context.getEnvironment().getProperty("spring.kafka.consumer.value-deserializer"))
                    .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
            assertThat(context.getEnvironment()
                    .getProperty("spring.kafka.consumer.enable-auto-commit", Boolean.class))
                    .isFalse();
            assertThat(context.getEnvironment().getProperty("spring.kafka.consumer.auto-offset-reset"))
                    .isEqualTo("latest");
            assertThat(context.getEnvironment().getProperty("spring.kafka.listener.ack-mode"))
                    .isEqualTo("record");
        });
    }

    @EnableConfigurationProperties(ProtocolAccountEventConsumerProperties.class)
    static class TestConfig {
    }
}
