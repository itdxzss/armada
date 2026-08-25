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
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-state-events.topic"))
                    .isTrue();
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-state-events.group-id"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-state-events.concurrency"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-group-sync-events.topic"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-group-sync-events.group-id"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-group-sync-events.concurrency"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-group-sync-events.max-poll-records"))
                    .isTrue();
            assertThat(context.getEnvironment()
                    .containsProperty("armada.protocol.kafka.account-event-errors.retry-interval-ms"))
                    .isTrue();

            ProtocolAccountStateEventConsumerProperties stateProperties =
                    context.getBean(ProtocolAccountStateEventConsumerProperties.class);
            ProtocolAccountGroupSyncEventConsumerProperties groupSyncProperties =
                    context.getBean(ProtocolAccountGroupSyncEventConsumerProperties.class);
            ProtocolAccountEventErrorProperties errorProperties =
                    context.getBean(ProtocolAccountEventErrorProperties.class);
            assertThat(stateProperties.getTopic())
                    .isEqualTo(ProtocolAccountStateEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(stateProperties.getGroupId())
                    .isEqualTo(ProtocolAccountStateEventConsumerProperties.DEFAULT_GROUP_ID);
            assertThat(stateProperties.getConcurrency())
                    .isEqualTo(ProtocolAccountStateEventConsumerProperties.DEFAULT_CONCURRENCY);
            assertThat(groupSyncProperties.getTopic())
                    .isEqualTo(ProtocolAccountGroupSyncEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(groupSyncProperties.getGroupId())
                    .isEqualTo(ProtocolAccountGroupSyncEventConsumerProperties.DEFAULT_GROUP_ID);
            assertThat(groupSyncProperties.getConcurrency())
                    .isEqualTo(ProtocolAccountGroupSyncEventConsumerProperties.DEFAULT_CONCURRENCY);
            assertThat(groupSyncProperties.getMaxPollRecords())
                    .isEqualTo(ProtocolAccountGroupSyncEventConsumerProperties.DEFAULT_MAX_POLL_RECORDS);
            assertThat(errorProperties.getRetryIntervalMs())
                    .isEqualTo(ProtocolAccountEventErrorProperties.DEFAULT_RETRY_INTERVAL_MS);
            assertThat(errorProperties.getMaxRetryAttempts())
                    .isEqualTo(ProtocolAccountEventErrorProperties.DEFAULT_MAX_RETRY_ATTEMPTS);
            assertThat(errorProperties.getDeadLetterTopicSuffix())
                    .isEqualTo(ProtocolAccountEventErrorProperties.DEFAULT_DEAD_LETTER_TOPIC_SUFFIX);
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

    @EnableConfigurationProperties({
            ProtocolAccountStateEventConsumerProperties.class,
            ProtocolAccountGroupSyncEventConsumerProperties.class,
            ProtocolAccountEventErrorProperties.class
    })
    static class TestConfig {
    }
}
