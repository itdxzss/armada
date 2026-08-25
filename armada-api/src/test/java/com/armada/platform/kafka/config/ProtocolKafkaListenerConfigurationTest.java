package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.kafka.consumer.account.ProtocolAccountEventConsumer;
import com.armada.platform.kafka.consumer.group.ProtocolGroupEventConsumer;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationEventConsumer;
import com.armada.platform.kafka.consumer.message.ProtocolMessageEventConsumer;
import com.armada.platform.kafka.consumer.pairing.ProtocolPairingEventConsumer;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 协议 Kafka listener 配置测试。
 */
class ProtocolKafkaListenerConfigurationTest {

    @Test
    void protocolEventListenersResolveTopicAndGroupFromEnvironmentProperties() throws NoSuchMethodException {
        assertListenerUsesProperties(
                ProtocolAccountEventConsumer.class,
                "onStateMessage",
                "${armada.protocol.kafka.account-state-events.topic:protocol.account.state.events.v1}",
                "${armada.protocol.kafka.account-state-events.group-id:armada-api-account-state-events}",
                "${armada.protocol.kafka.account-state-events.concurrency:4}");
        assertListenerUsesProperties(
                ProtocolAccountEventConsumer.class,
                "onGroupSyncMessage",
                "${armada.protocol.kafka.account-group-sync-events.topic:protocol.account.group-sync.events.v1}",
                "${armada.protocol.kafka.account-group-sync-events.group-id:armada-api-account-group-sync-events}",
                "${armada.protocol.kafka.account-group-sync-events.concurrency:4}");
        assertThat(listener(
                ProtocolAccountEventConsumer.class,
                "onGroupSyncMessage").properties())
                .containsExactly(
                        "max.poll.records=${armada.protocol.kafka.account-group-sync-events.max-poll-records:1}");
        assertListenerUsesProperties(
                ProtocolGroupEventConsumer.class,
                "onMessage",
                "${armada.protocol.kafka.group-events.topic:protocol.group.events.v1}",
                "${armada.protocol.kafka.group-events.group-id:armada-api-group-events}",
                "${armada.protocol.kafka.group-events.concurrency:3}");
        assertListenerUsesProperties(
                ProtocolNormalGroupCreationEventConsumer.class,
                "onMessage",
                "${armada.normal-group-creation.kafka.result-topic:protocol.normal-group.events.v1}",
                "${armada.normal-group-creation.kafka.result-group-id:armada-api-normal-group-results}",
                "${armada.normal-group-creation.kafka.result-concurrency:4}");
        assertListenerUsesProperties(
                ProtocolMessageEventConsumer.class,
                "onMessage",
                "${armada.protocol.kafka.message-events.topic:protocol.message.events.v1}",
                "${armada.protocol.kafka.message-events.group-id:armada-api-message-events}",
                "");
        assertListenerUsesProperties(
                ProtocolPairingEventConsumer.class,
                "onMessage",
                "${armada.protocol.kafka.pairing-events.topic:protocol.pairing.events.v1}",
                "${armada.protocol.kafka.pairing-events.group-id:armada-api-pairing-events}",
                "");
    }

    private static void assertListenerUsesProperties(Class<?> listenerType,
                                                     String methodName,
                                                     String expectedTopic,
                                                     String expectedGroupId,
                                                     String expectedConcurrency) throws NoSuchMethodException {
        KafkaListener listener = listener(listenerType, methodName);

        assertThat(listener).isNotNull();
        assertThat(listener.topics()).containsExactly(expectedTopic);
        assertThat(listener.groupId()).isEqualTo(expectedGroupId);
        assertThat(listener.concurrency()).isEqualTo(expectedConcurrency);
    }

    private static KafkaListener listener(Class<?> listenerType,
                                          String methodName) throws NoSuchMethodException {
        Method onMessage = listenerType.getDeclaredMethod(methodName, String.class, String.class);
        return onMessage.getAnnotation(KafkaListener.class);
    }
}
