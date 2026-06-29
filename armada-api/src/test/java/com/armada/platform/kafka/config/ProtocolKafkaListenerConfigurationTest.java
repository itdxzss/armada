package com.armada.platform.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.kafka.consumer.account.ProtocolAccountEventConsumer;
import com.armada.platform.kafka.consumer.group.ProtocolGroupEventConsumer;
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
                "${armada.protocol.kafka.account-events.topic:protocol.account.events.v1}",
                "${armada.protocol.kafka.account-events.group-id:armada-api-account-events}");
        assertListenerUsesProperties(
                ProtocolGroupEventConsumer.class,
                "${armada.protocol.kafka.group-events.topic:protocol.group.events.v1}",
                "${armada.protocol.kafka.group-events.group-id:armada-api-group-events}");
    }

    private static void assertListenerUsesProperties(Class<?> listenerType,
                                                     String expectedTopic,
                                                     String expectedGroupId) throws NoSuchMethodException {
        Method onMessage = listenerType.getDeclaredMethod("onMessage", String.class);
        KafkaListener listener = onMessage.getAnnotation(KafkaListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.topics()).containsExactly(expectedTopic);
        assertThat(listener.groupId()).isEqualTo(expectedGroupId);
    }
}
