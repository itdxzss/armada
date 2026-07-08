package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * Android 协议账号命令 Kafka properties 测试。
 */
class ProtocolAndroidCommandPropertiesTest {

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
    void bindsAndroidCommandTopic() {
        contextRunner
                .withPropertyValues("armada.protocol.kafka.android-commands.topic=protocol.android.commands.test")
                .run(context -> {
                    ProtocolAndroidCommandProperties properties =
                            context.getBean(ProtocolAndroidCommandProperties.class);

                    assertThat(properties.getTopic()).isEqualTo("protocol.android.commands.test");
                });
    }

    @Test
    void applicationYamlExposesAndroidCommandTopicDefault() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment().containsProperty("armada.protocol.kafka.android-commands.topic"))
                    .isTrue();
            assertThat(context.getBean(ProtocolAndroidCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_TOPIC);
        });
    }

    @EnableConfigurationProperties(ProtocolAndroidCommandProperties.class)
    static class TestConfig {
    }
}
