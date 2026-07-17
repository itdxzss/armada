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
    void bindsAndroidCommandTopics() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.kafka.android-commands.lifecycle-topic=lifecycle.test",
                        "armada.protocol.kafka.android-commands.message-topic=message.test",
                        "armada.protocol.kafka.android-commands.group-join-topic=group-join.test")
                .run(context -> {
                    ProtocolAndroidCommandProperties properties =
                            context.getBean(ProtocolAndroidCommandProperties.class);

                    assertThat(properties.getLifecycleTopic()).isEqualTo("lifecycle.test");
                    assertThat(properties.getMessageTopic()).isEqualTo("message.test");
                    assertThat(properties.getGroupJoinTopic()).isEqualTo("group-join.test");
                });
    }

    @Test
    void applicationYamlExposesAndroidCommandTopicDefaults() {
        applicationYamlContextRunner.run(context -> {
            assertThat(context.getEnvironment()
                            .containsProperty("armada.protocol.kafka.android-commands.lifecycle-topic"))
                    .isTrue();
            assertThat(context.getEnvironment()
                            .containsProperty("armada.protocol.kafka.android-commands.message-topic"))
                    .isTrue();
            assertThat(context.getEnvironment()
                            .containsProperty("armada.protocol.kafka.android-commands.group-join-topic"))
                    .isTrue();
            ProtocolAndroidCommandProperties properties =
                    context.getBean(ProtocolAndroidCommandProperties.class);
            assertThat(properties.getLifecycleTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC);
            assertThat(properties.getMessageTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC);
            assertThat(properties.getGroupJoinTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
        });
    }

    @Test
    void rejectsBlankAndroidCommandTopic() {
        contextRunner
                .withPropertyValues("armada.protocol.kafka.android-commands.message-topic= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains("Android 命令 topic 不能为空");
                });
    }

    @Test
    void rejectsDuplicateAndroidCommandTopics() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.kafka.android-commands.lifecycle-topic=duplicate.test",
                        "armada.protocol.kafka.android-commands.message-topic=duplicate.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains("Android 命令 topic 必须互不重复");
                });
    }

    @Test
    void ignoresRemovedSharedAndroidCommandTopicProperty() {
        contextRunner
                .withPropertyValues("armada.protocol.kafka.android-commands.topic=legacy.test")
                .run(context -> {
                    ProtocolAndroidCommandProperties properties =
                            context.getBean(ProtocolAndroidCommandProperties.class);
                    assertThat(properties.getLifecycleTopic())
                            .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC);
                    assertThat(properties.getMessageTopic())
                            .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC);
                    assertThat(properties.getGroupJoinTopic())
                            .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
                });
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @EnableConfigurationProperties(ProtocolAndroidCommandProperties.class)
    static class TestConfig {
    }
}
