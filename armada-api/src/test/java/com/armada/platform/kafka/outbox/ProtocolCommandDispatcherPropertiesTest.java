package com.armada.platform.kafka.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议命令 dispatcher 配置测试。
 */
class ProtocolCommandDispatcherPropertiesTest {

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
    void bindsDispatcherProperties() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.command-dispatcher.publisher-id=publisher-a",
                        "armada.protocol.command-dispatcher.immediate-enabled=false",
                        "armada.protocol.command-dispatcher.batch-size=50",
                        "armada.protocol.command-dispatcher.max-batches-per-drain=4",
                        "armada.protocol.command-dispatcher.max-retry-count=5",
                        "armada.protocol.command-dispatcher.retry-delay-ms=15000",
                        "armada.protocol.command-dispatcher.locked-timeout-ms=45000",
                        "armada.protocol.command-dispatcher.scheduler.enabled=true",
                        "armada.protocol.command-dispatcher.scheduler.fixed-delay-ms=7000")
                .run(context -> {
                    ProtocolCommandDispatcherProperties properties =
                            context.getBean(ProtocolCommandDispatcherProperties.class);

                    assertThat(properties.getPublisherId()).isEqualTo("publisher-a");
                    assertThat(properties.isImmediateEnabled()).isFalse();
                    assertThat(properties.getBatchSize()).isEqualTo(50);
                    assertThat(properties.getMaxBatchesPerDrain()).isEqualTo(4);
                    assertThat(properties.getMaxRetryCount()).isEqualTo(5);
                    assertThat(properties.getRetryDelayMs()).isEqualTo(15_000L);
                    assertThat(properties.getLockedTimeoutMs()).isEqualTo(45_000L);
                    assertThat(properties.getScheduler().isEnabled()).isTrue();
                    assertThat(properties.getScheduler().getFixedDelayMs()).isEqualTo(7_000L);
                });
    }

    @Test
    void applicationYamlKeepsLowFrequencySchedulerAndImmediateDispatchEnabled() {
        applicationYamlContextRunner.run(context -> {
            ProtocolCommandDispatcherProperties properties =
                    context.getBean(ProtocolCommandDispatcherProperties.class);

            assertThat(properties.isImmediateEnabled()).isTrue();
            assertThat(properties.getBatchSize()).isEqualTo(100);
            assertThat(properties.getMaxBatchesPerDrain()).isEqualTo(5);
            assertThat(properties.getMaxRetryCount()).isEqualTo(3);
            assertThat(properties.getRetryDelayMs()).isEqualTo(30_000L);
            assertThat(properties.getLockedTimeoutMs()).isEqualTo(60_000L);
            assertThat(properties.getScheduler().isEnabled()).isTrue();
            assertThat(properties.getScheduler().getFixedDelayMs()).isEqualTo(10_000L);
        });
    }

    @EnableConfigurationProperties(ProtocolCommandDispatcherProperties.class)
    static class TestConfig {
    }
}
