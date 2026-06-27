package com.armada.platform.protocol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 协议命令 Kafka publisher 单测。
 *
 * <p>Slice 4 只验证 outbox row 如何变成 Kafka command envelope 并交给 KafkaTemplate。
 * 不扫描 outbox,不标记 SENT/RETRY/DEAD,也不接账号上线入口。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolCommandPublisherTest {

    @Mock
    private KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate;

    private ProtocolCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        ProtocolCommandPublisherProperties properties = new ProtocolCommandPublisherProperties();
        properties.setSendTimeoutMs(1_000);
        publisher = new ProtocolCommandPublisher(kafkaTemplate, new ObjectMapper(), properties);
    }

    @Test
    void publish_validOutboxRow_sendsCommandEnvelopeToConfiguredTopicAndKey() {
        ProtocolCommandOutbox row = outboxRow("{\"accountId\":100,\"proxyId\":7,\"source\":\"manual_online\"}");
        when(kafkaTemplate.send(eq("protocol.account.commands.v1"), eq("acc_100"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProtocolCommandPublishResult result = publisher.publish(row);

        assertThat(result.commandId()).isEqualTo("cmd_100");
        assertThat(result.topic()).isEqualTo("protocol.account.commands.v1");
        assertThat(result.kafkaKey()).isEqualTo("acc_100");

        ArgumentCaptor<ProtocolCommandEnvelope> captor = ArgumentCaptor.forClass(ProtocolCommandEnvelope.class);
        verify(kafkaTemplate).send(eq("protocol.account.commands.v1"), eq("acc_100"), captor.capture());
        ProtocolCommandEnvelope envelope = captor.getValue();
        assertThat(envelope.commandId()).isEqualTo("cmd_100");
        assertThat(envelope.batchId()).isEqualTo("batch_1");
        assertThat(envelope.commandType()).isEqualTo("account.online.requested");
        assertThat(envelope.aggregateType()).isEqualTo("ACCOUNT");
        assertThat(envelope.aggregateId()).isEqualTo(100L);
        assertThat(envelope.protocolAccountId()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("accountId").asLong()).isEqualTo(100L);
        assertThat(envelope.payload().get("proxyId").asLong()).isEqualTo(7L);
        assertThat(envelope.payload().get("source").asText()).isEqualTo("manual_online");
    }

    @Test
    void publish_invalidPayloadJson_throwsValidationBeforeKafkaSend() {
        ProtocolCommandOutbox row = outboxRow("{not-json");

        assertThatThrownBy(() -> publisher.publish(row))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("payload JSON 非法");
    }

    @Test
    void publish_kafkaSendFails_throwsProtocolExceptionWithoutLeakingPayload() {
        ProtocolCommandOutbox row = outboxRow("{\"accountId\":100,\"proxyId\":7,\"source\":\"manual_online\"}");
        CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(eq("protocol.account.commands.v1"), eq("acc_100"), any()))
                .thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(row))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("协议命令 Kafka 发送失败")
                .hasMessageContaining("cmd_100")
                .hasMessageNotContaining("proxyId")
                .hasMessageNotContaining("manual_online");
    }

    private static ProtocolCommandOutbox outboxRow(String payloadJson) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId("cmd_100");
        row.setBatchId("batch_1");
        row.setCommandType("account.online.requested");
        row.setAggregateType("ACCOUNT");
        row.setAggregateId(100L);
        row.setKafkaTopic("protocol.account.commands.v1");
        row.setKafkaKey("acc_100");
        row.setProtocolAccountId("acc_100");
        row.setPayloadJson(payloadJson);
        return row;
    }
}
