package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 协议命令 Kafka 装配测试。
 */
class ProtocolKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProtocolKafkaConfiguration.class);

    @Test
    void registersOutboxPropertiesAndDispatchExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProtocolCommandPublisherProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandDispatcherProperties.class);
            assertThat(context).hasSingleBean(ProtocolAccountEventConsumerProperties.class);
            assertThat(context.getBean(ProtocolAccountEventConsumerProperties.class).getTopic())
                    .isEqualTo(ProtocolAccountEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(context).hasBean("protocolCommandDispatchExecutor");
            assertThat(context.getBean("protocolCommandDispatchExecutor")).isInstanceOf(Executor.class);
        });
    }
}
