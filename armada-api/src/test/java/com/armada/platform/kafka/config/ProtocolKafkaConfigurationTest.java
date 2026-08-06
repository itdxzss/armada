package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
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
            assertThat(context).hasSingleBean(ProtocolAndroidCommandProperties.class);
            assertThat(context).hasSingleBean(ProtocolMasterCommandProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandPublisherProperties.class);
            assertThat(context).hasSingleBean(ProtocolCommandDispatcherProperties.class);
            assertThat(context).hasSingleBean(ProtocolAccountStateEventConsumerProperties.class);
            assertThat(context).hasSingleBean(ProtocolAccountGroupSyncEventConsumerProperties.class);
            assertThat(context).hasSingleBean(ProtocolAccountEventErrorProperties.class);
            assertThat(context).hasSingleBean(ProtocolGroupEventConsumerProperties.class);
            assertThat(context).hasSingleBean(NormalGroupCreationKafkaProperties.class);
            assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
            assertThat(context.getBean(ProtocolAccountCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolAccountCommandProperties.DEFAULT_TOPIC);
            ProtocolAndroidCommandProperties androidProperties =
                    context.getBean(ProtocolAndroidCommandProperties.class);
            assertThat(androidProperties.getLifecycleTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC);
            assertThat(androidProperties.getMessageTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC);
            assertThat(androidProperties.getGroupJoinTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
            assertThat(androidProperties.getGroupActionTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            assertThat(context.getBean(ProtocolMasterCommandProperties.class).getTopic())
                    .isEqualTo(ProtocolMasterCommandProperties.DEFAULT_TOPIC);
            ProtocolAccountStateEventConsumerProperties stateProperties =
                    context.getBean(ProtocolAccountStateEventConsumerProperties.class);
            assertThat(stateProperties.getTopic())
                    .isEqualTo(ProtocolAccountStateEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(stateProperties.getConcurrency()).isEqualTo(4);
            ProtocolAccountGroupSyncEventConsumerProperties groupSyncProperties =
                    context.getBean(ProtocolAccountGroupSyncEventConsumerProperties.class);
            assertThat(groupSyncProperties.getTopic())
                    .isEqualTo(ProtocolAccountGroupSyncEventConsumerProperties.DEFAULT_TOPIC);
            assertThat(groupSyncProperties.getConcurrency()).isEqualTo(4);
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
                        "armada.protocol.kafka.account-state-events.topic=protocol.account.state.events.test",
                        "armada.protocol.kafka.account-state-events.group-id=armada-api-account-state-events-test",
                        "armada.protocol.kafka.account-state-events.concurrency=6",
                        "armada.protocol.kafka.account-group-sync-events.topic=protocol.account.group-sync.events.test",
                        "armada.protocol.kafka.account-group-sync-events.group-id=armada-api-account-group-sync-events-test",
                        "armada.protocol.kafka.account-group-sync-events.concurrency=3",
                        "armada.protocol.kafka.account-event-errors.retry-interval-ms=250",
                        "armada.protocol.kafka.account-event-errors.max-retry-attempts=5",
                        "armada.protocol.kafka.account-event-errors.dead-letter-topic-suffix=.dead")
                .run(context -> {
                    ProtocolAccountStateEventConsumerProperties stateProperties =
                            context.getBean(ProtocolAccountStateEventConsumerProperties.class);
                    ProtocolAccountGroupSyncEventConsumerProperties groupSyncProperties =
                            context.getBean(ProtocolAccountGroupSyncEventConsumerProperties.class);
                    ProtocolAccountEventErrorProperties errorProperties =
                            context.getBean(ProtocolAccountEventErrorProperties.class);

                    assertThat(stateProperties.getTopic()).isEqualTo("protocol.account.state.events.test");
                    assertThat(stateProperties.getGroupId()).isEqualTo("armada-api-account-state-events-test");
                    assertThat(stateProperties.getConcurrency()).isEqualTo(6);
                    assertThat(groupSyncProperties.getTopic()).isEqualTo("protocol.account.group-sync.events.test");
                    assertThat(groupSyncProperties.getGroupId())
                            .isEqualTo("armada-api-account-group-sync-events-test");
                    assertThat(groupSyncProperties.getConcurrency()).isEqualTo(3);
                    assertThat(errorProperties.getRetryIntervalMs()).isEqualTo(250L);
                    assertThat(errorProperties.getMaxRetryAttempts()).isEqualTo(5L);
                    assertThat(errorProperties.getDeadLetterTopicSuffix()).isEqualTo(".dead");
                });
    }

    @Test
    void normalGroupTopicsAreDedicatedFromEveryExistingProtocolTopic() throws Exception {
        ProtocolKafkaConfiguration configuration = new ProtocolKafkaConfiguration();
        NormalGroupCreationKafkaProperties normal = new NormalGroupCreationKafkaProperties();
        ProtocolAccountCommandProperties account = new ProtocolAccountCommandProperties();
        ProtocolMasterCommandProperties master = new ProtocolMasterCommandProperties();
        ProtocolAndroidCommandProperties android = new ProtocolAndroidCommandProperties();
        ProtocolAccountStateEventConsumerProperties state =
                new ProtocolAccountStateEventConsumerProperties();
        ProtocolAccountGroupSyncEventConsumerProperties sync =
                new ProtocolAccountGroupSyncEventConsumerProperties();
        ProtocolGroupEventConsumerProperties group = new ProtocolGroupEventConsumerProperties();
        ProtocolMessageEventConsumerProperties message =
                new ProtocolMessageEventConsumerProperties();

        InitializingBean valid = configuration.normalGroupKafkaTopicIsolationValidator(
                normal, account, master, android, state, sync, group, message);
        assertThatCode(valid::afterPropertiesSet).doesNotThrowAnyException();

        normal.setWebCommandTopic(master.getTopic());
        InitializingBean conflicting = configuration.normalGroupKafkaTopicIsolationValidator(
                normal, account, master, android, state, sync, group, message);
        assertThatThrownBy(conflicting::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得复用");
    }
}
