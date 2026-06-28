package com.armada.platform.kafka.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 协议命令 outbox Kafka 装配测试。
 */
class ProtocolKafkaOutboxConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProtocolKafkaOutboxConfiguration.class);

    @Test
    void registersOutboxPropertiesAndDispatchExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProtocolCommandPublisherProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandDispatcherProperties.class);
            assertThat(context).hasBean("protocolCommandDispatchExecutor");
            assertThat(context.getBean("protocolCommandDispatchExecutor")).isInstanceOf(Executor.class);
        });
    }
}
