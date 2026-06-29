package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * 协议 master 命令 Kafka properties 测试。
 */
class ProtocolMasterCommandPropertiesTest {

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
    void bindsMasterCommandTopic() {
        contextRunner
                .withPropertyValues("armada.protocol.kafka.master-commands.topic=protocol.master.commands.test")
                .run(context -> {
                    ProtocolMasterCommandProperties properties =
                            context.getBean(ProtocolMasterCommandProperties.class);

                    assertThat(properties.getTopic()).isEqualTo("protocol.master.commands.test");
                });
    }

    @Test
    void applicationYamlExposesMasterCommandTopicDefault() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.master-commands.topic"))
                    .isTrue();
            assertThat(context.getBean(ProtocolMasterCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolMasterCommandProperties.DEFAULT_TOPIC);
        });
    }

    @EnableConfigurationProperties(ProtocolMasterCommandProperties.class)
    static class TestConfig {
    }
}
