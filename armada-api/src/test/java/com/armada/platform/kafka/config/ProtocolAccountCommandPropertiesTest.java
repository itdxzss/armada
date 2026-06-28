package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * 协议账号命令 Kafka properties 测试。
 */
class ProtocolAccountCommandPropertiesTest {

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
                } catch (IOException ex) {
                    throw new IllegalStateException("读取 application.yml 失败", ex);
                }
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsAccountCommandTopic() {
        contextRunner
                .withPropertyValues("armada.protocol.kafka.account-commands.topic=protocol.account.commands.test")
                .run(context -> {
                    ProtocolAccountCommandProperties properties =
                            context.getBean(ProtocolAccountCommandProperties.class);

                    assertThat(properties.getTopic()).isEqualTo("protocol.account.commands.test");
                });
    }

    @Test
    void applicationYamlExposesAccountCommandTopicDefault() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.account-commands.topic"))
                    .isTrue();
            assertThat(context.getBean(ProtocolAccountCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolAccountCommandProperties.DEFAULT_TOPIC);
        });
    }

    @EnableConfigurationProperties(ProtocolAccountCommandProperties.class)
    static class TestConfig {
    }
}
