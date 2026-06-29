package com.armada.platform.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.platform.kafka.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.model.entity.AccountCredential;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishOutcome;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.slf4j.LoggerFactory;

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

    @Mock
    private AccountCredentialMapper credentialMapper;

    @Mock
    private IpProxyMapper ipProxyMapper;

    private ProtocolCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        ProtocolCommandPublisherProperties properties = new ProtocolCommandPublisherProperties();
        properties.setSendTimeoutMs(1_000);
        publisher = new ProtocolCommandPublisher(
                kafkaTemplate,
                new ObjectMapper(),
                properties,
                credentialMapper,
                ipProxyMapper,
                new ProxyResolver());
    }

    @Test
    void publish_validOutboxRow_sendsCommandEnvelopeToConfiguredTopicAndKey() {
        ProtocolCommandOutbox row = passthroughOutboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                + "\"source\":\"scheduled_account_group_sync\"}");
        when(kafkaTemplate.send(eq("protocol.master.commands.v1"), eq("acc_100"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProtocolCommandPublishResult result = publisher.publish(row);

        assertThat(result.commandId()).isEqualTo("cmd_100");
        assertThat(result.topic()).isEqualTo("protocol.master.commands.v1");
        assertThat(result.kafkaKey()).isEqualTo("acc_100");

        ArgumentCaptor<ProtocolCommandEnvelope> captor = ArgumentCaptor.forClass(ProtocolCommandEnvelope.class);
        verify(kafkaTemplate).send(eq("protocol.master.commands.v1"), eq("acc_100"), captor.capture());
        ProtocolCommandEnvelope envelope = captor.getValue();
        assertThat(envelope.commandId()).isEqualTo("cmd_100");
        assertThat(envelope.batchId()).isEqualTo("batch_1");
        assertThat(envelope.commandType()).isEqualTo("account.groups_sync.requested");
        assertThat(envelope.aggregateType()).isEqualTo("ACCOUNT");
        assertThat(envelope.aggregateId()).isEqualTo(100L);
        assertThat(envelope.protocolAccountId()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("accountId").asLong()).isEqualTo(100L);
        assertThat(envelope.payload().get("protocolAccountId").asText()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("source").asText()).isEqualTo("scheduled_account_group_sync");
    }

    @Test
    void publish_offlineOutboxRow_sendsOfflineCommandEnvelopeWithoutProxyPayload() {
        ProtocolCommandOutbox row = outboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                + "\"source\":\"batch_offline\"}");
        row.setCommandType("account.offline.requested");
        row.setKafkaTopic("protocol.master.commands.v1");
        when(kafkaTemplate.send(eq("protocol.master.commands.v1"), eq("acc_100"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProtocolCommandPublishResult result = publisher.publish(row);

        assertThat(result.commandId()).isEqualTo("cmd_100");
        ArgumentCaptor<ProtocolCommandEnvelope> captor = ArgumentCaptor.forClass(ProtocolCommandEnvelope.class);
        verify(kafkaTemplate).send(eq("protocol.master.commands.v1"), eq("acc_100"), captor.capture());
        ProtocolCommandEnvelope envelope = captor.getValue();
        assertThat(envelope.commandType()).isEqualTo("account.offline.requested");
        assertThat(envelope.aggregateType()).isEqualTo("ACCOUNT");
        assertThat(envelope.aggregateId()).isEqualTo(100L);
        assertThat(envelope.protocolAccountId()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("accountId").asLong()).isEqualTo(100L);
        assertThat(envelope.payload().get("protocolAccountId").asText()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("source").asText()).isEqualTo("batch_offline");
        assertThat(envelope.payload().has("proxyId")).isFalse();
        assertThat(envelope.payload().has("credentialFormat")).isFalse();
    }

    @Test
    void publishBatch_onlineRowsHydratesCredentialsAndProxiesWithBulkQueriesBeforeSending() {
        ProtocolCommandOutbox first = outboxRow(
                "cmd_100",
                1L,
                100L,
                "acc_100",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                        + "\"credentialFormat\":\"BAILEYS_JSON\",\"proxyId\":7,\"source\":\"batch_online\"}");
        ProtocolCommandOutbox second = outboxRow(
                "cmd_101",
                1L,
                101L,
                "acc_101",
                "{\"accountId\":101,\"protocolAccountId\":\"acc_101\","
                        + "\"credentialFormat\":\"PARAMS\",\"proxyId\":8,\"source\":\"batch_online\"}");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(100L, 101L)))
                .thenReturn(List.of(
                        credential(100L, 2, "{\"creds\":{\"noiseKey\":\"n1\"},\"keys\":{}}"),
                        credential(101L, 3, "{\"login\":\"raw\"}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L, 8L)))
                .thenReturn(List.of(
                        proxy(7L, 100L, 2, "proxy-a.internal", 1080, "user-a", "pass_session-Aaa111", "印度"),
                        proxy(8L, 101L, 1, "proxy-b.internal", 8080, "user-b", "pass_session-Bbb222", "新加坡")));
        when(kafkaTemplate.send(eq("protocol.account.commands.v1"), eq("acc_100"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq("protocol.account.commands.v1"), eq("acc_101"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<ProtocolCommandPublishOutcome> outcomes = publisher.publishBatch(List.of(first, second));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        verify(credentialMapper).selectByTenantAndAccountIds(1L, List.of(100L, 101L));
        verify(ipProxyMapper).selectActiveByTenantAndIds(1L, List.of(7L, 8L));
        ArgumentCaptor<ProtocolCommandEnvelope> captor = ArgumentCaptor.forClass(ProtocolCommandEnvelope.class);
        verify(kafkaTemplate).send(eq("protocol.account.commands.v1"), eq("acc_100"), captor.capture());
        ProtocolCommandEnvelope firstEnvelope = captor.getValue();
        assertThat(firstEnvelope.payload().get("format").asText()).isEqualTo("baileys_json");
        assertThat(firstEnvelope.payload().get("credential").get("creds").get("noiseKey").asText()).isEqualTo("n1");
        assertThat(firstEnvelope.payload().get("proxy").get("protocol").asText()).isEqualTo("socks5");
        assertThat(firstEnvelope.payload().get("proxy").get("url").asText())
                .isEqualTo("socks5://user-a:pass_session-Aaa111@proxy-a.internal:1080");
        assertThat(firstEnvelope.payload().get("proxy").get("sessionId").asText()).isEqualTo("Aaa111");
        assertThat(firstEnvelope.payload().toString())
                .doesNotContain("credentialJson")
                .doesNotContain("credentialFormat")
                .doesNotContain("\"proxyId\"");
    }

    @Test
    void publish_success_doesNotWritePerMessageInfoLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProtocolCommandPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProtocolCommandOutbox row = passthroughOutboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                    + "\"source\":\"scheduled_account_group_sync\"}");
            when(kafkaTemplate.send(eq("protocol.master.commands.v1"), eq("acc_100"), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            publisher.publish(row);

            assertThat(appender.list)
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO)
                            && event.getFormattedMessage().contains("协议命令 Kafka 发送成功"));
        } finally {
            logger.detachAppender(appender);
        }
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
        ProtocolCommandOutbox row = passthroughOutboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                + "\"source\":\"scheduled_account_group_sync\"}");
        CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(eq("protocol.master.commands.v1"), eq("acc_100"), any()))
                .thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(row))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("协议命令 Kafka 发送失败")
                .hasMessageContaining("cmd_100")
                .hasMessageNotContaining("protocolAccountId")
                .hasMessageNotContaining("scheduled_account_group_sync");
    }

    private static ProtocolCommandOutbox outboxRow(String payloadJson) {
        return outboxRow("cmd_100", 1L, 100L, "acc_100", payloadJson);
    }

    private static ProtocolCommandOutbox passthroughOutboxRow(String payloadJson) {
        ProtocolCommandOutbox row = outboxRow(payloadJson);
        row.setCommandType("account.groups_sync.requested");
        row.setKafkaTopic("protocol.master.commands.v1");
        return row;
    }

    private static ProtocolCommandOutbox outboxRow(String commandId,
                                                   Long tenantId,
                                                   Long accountId,
                                                   String protocolAccountId,
                                                   String payloadJson) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId(commandId);
        row.setBatchId("batch_1");
        row.setCommandType("account.online.requested");
        row.setAggregateType("ACCOUNT");
        row.setAggregateId(accountId);
        row.setKafkaTopic("protocol.account.commands.v1");
        row.setKafkaKey(protocolAccountId);
        row.setProtocolAccountId(protocolAccountId);
        row.setTenantId(tenantId);
        row.setPayloadJson(payloadJson);
        return row;
    }

    private static AccountCredential credential(Long accountId, Integer format, String json) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(accountId);
        credential.setCredFormat(format);
        credential.setCredsJson(json);
        return credential;
    }

    private static IpProxy proxy(Long id,
                                 Long boundAccountId,
                                 Integer protocol,
                                 String host,
                                 Integer port,
                                 String username,
                                 String password,
                                 String region) {
        IpProxy proxy = new IpProxy();
        proxy.setId(id);
        proxy.setBoundAccountId(boundAccountId);
        proxy.setStatus(IpProxyStatus.IN_USE.code());
        proxy.setProtocol(protocol);
        proxy.setHost(host);
        proxy.setPort(port);
        proxy.setUsername(username);
        proxy.setPassword(password);
        proxy.setRegion(region);
        return proxy;
    }
}
