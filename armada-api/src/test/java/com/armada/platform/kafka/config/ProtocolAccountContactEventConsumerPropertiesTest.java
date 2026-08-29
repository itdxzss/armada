package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/**
 * 通讯录快照事件消费者配置测试。
 *
 * <p>这份配置一旦从 {@code application.yml} 里消失，部署侧就没有 env 口子可以改 topic，
 * 而且不会有任何报错——消费者会安静地退回代码里的默认值。这里把它钉住。</p>
 */
class ProtocolAccountContactEventConsumerPropertiesTest {

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
    void bindsOverriddenTopicAndGroup() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol.kafka.account-contact-events.topic=contact.test",
                        "armada.protocol.kafka.account-contact-events.group-id=group.test",
                        "armada.protocol.kafka.account-contact-events.concurrency=3",
                        "armada.protocol.kafka.account-contact-events.max-poll-records=5")
                .run(context -> {
                    ProtocolAccountContactEventConsumerProperties properties =
                            context.getBean(ProtocolAccountContactEventConsumerProperties.class);

                    assertThat(properties.getTopic()).isEqualTo("contact.test");
                    assertThat(properties.getGroupId()).isEqualTo("group.test");
                    assertThat(properties.getConcurrency()).isEqualTo(3);
                    assertThat(properties.getMaxPollRecords()).isEqualTo(5);
                });
    }

    @Test
    void applicationYamlExposesEveryContactConsumerKnob() {
        applicationYamlContextRunner.run(context -> {
            // 四个键都要在 yml 里出现，部署侧才有 env 覆盖点
            for (String key : new String[] {
                    "armada.protocol.kafka.account-contact-events.topic",
                    "armada.protocol.kafka.account-contact-events.group-id",
                    "armada.protocol.kafka.account-contact-events.concurrency",
                    "armada.protocol.kafka.account-contact-events.max-poll-records"}) {
                assertThat(context.getEnvironment().containsProperty(key))
                        .as("application.yml 缺少 %s", key)
                        .isTrue();
            }

            ProtocolAccountContactEventConsumerProperties properties =
                    context.getBean(ProtocolAccountContactEventConsumerProperties.class);
            assertThat(properties.getTopic())
                    .isEqualTo(ProtocolAccountContactEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(properties.getGroupId())
                    .isEqualTo(ProtocolAccountContactEventConsumerProperties.DEFAULT_GROUP_ID);
            // 一条消息是一片最多 500 人的快照，落库要走多批写入；
            // 放大 poll 批量会让一批处理超过 max.poll.interval.ms 从而反复 rebalance
            assertThat(properties.getMaxPollRecords()).isEqualTo(1);
        });
    }

    @Test
    void privateCapabilityGateStaysFailClosedByDefault() {
        applicationYamlContextRunner.run(context -> {
            // 白名单必须在 yml 里有 env 口子，但默认值仍是空串：
            // 没真机验过的协议后端不许发私聊，通讯录与超链共用这一个开关
            assertThat(context.getEnvironment()
                    .containsProperty("armada.hyperlink.private-capable-backends")).isTrue();
            assertThat(context.getEnvironment()
                    .getProperty("armada.hyperlink.private-capable-backends")).isEmpty();
        });
    }

    @EnableConfigurationProperties(ProtocolAccountContactEventConsumerProperties.class)
    static class TestConfig {
    }
}
