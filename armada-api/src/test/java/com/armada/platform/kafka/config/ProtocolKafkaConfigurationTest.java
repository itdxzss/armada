package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 协议命令 Kafka 装配测试。
 */
class ProtocolKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProtocolKafkaConfiguration.class);

    @Test
    void registersOutboxPropertiesAndDispatchExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProtocolAccountCommandProperties.class);
            assertThat(context).hasSingleBean(ProtocolMasterCommandProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandPublisherProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandDispatcherProperties.class);
            assertThat(context).hasSingleBean(ProtocolAccountEventConsumerProperties.class);
            assertThat(context).hasSingleBean(ProtocolGroupEventConsumerProperties.class);
            assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
            assertThat(context.getBean(ProtocolAccountCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolAccountCommandProperties.DEFAULT_TOPIC);
            assertThat(context.getBean(ProtocolMasterCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolMasterCommandProperties.DEFAULT_TOPIC);
            assertThat(context.getBean(ProtocolAccountEventConsumerProperties.class).getTopic())
                    .isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(context.getBean(ProtocolGroupEventConsumerProperties.class).getTopic())
                    .isEqualTo(ProtocolGroupEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(context).hasBean("protocolCommandDispatchExecutor");
            assertThat(context.getBean("protocolCommandDispatchExecutor")).isInstanceOf(Executor.class);
        });
    }

    @Test
    void kafkaProfileRegistersAccountEventConsumerErrorHandler() {
        contextRunner
                .withPropertyValues("spring.profiles.active=kafka")
                .run(context -> {
                    assertThat(context).hasSingleBean(CommonErrorHandler.class);
                    assertThat(context.getBean(CommonErrorHandler.class)).isInstanceOf(DefaultErrorHandler.class);
                });
    }

    @Test
    void bindsAccountEventConsumerRetryAndDeadLetterProperties() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.kafka.account-events.topic=protocol.account.events.test",
                        "armada.protocol.kafka.account-events.group-id=armada-api-account-events-test",
                        "armada.protocol.kafka.account-events.retry-interval-ms=250",
                        "armada.protocol.kafka.account-events.max-retry-attempts=5",
                        "armada.protocol.kafka.account-events.dead-letter-topic-suffix=.dead")
                .run(context -> {
                    ProtocolAccountEventConsumerProperties properties =
                            context.getBean(ProtocolAccountEventConsumerProperties.class);

                    assertThat(properties.getTopic()).isEqualTo("protocol.account.events.test");
                    assertThat(properties.getGroupId()).isEqualTo("armada-api-account-events-test");
                    assertThat(properties.getRetryIntervalMs()).isEqualTo(250L);
                    assertThat(properties.getMaxRetryAttempts()).isEqualTo(5L);
                    assertThat(properties.getDeadLetterTopicSuffix()).isEqualTo(".dead");
                });
    }
}
