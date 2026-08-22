package com.armada.platform.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.platform.kafka.config.ProtocolCommandPublisherProperties;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.account.model.vo.AccountGroupBaselineStateRow;
import com.armada.account.model.entity.AccountCredential;
import com.armada.platform.protocol.model.command.ProtocolCommandEnvelope;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishResult;
import com.armada.platform.protocol.model.result.ProtocolCommandPublishOutcome;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

/**
 * 协议命令 Kafka publisher 单测。
 *
 * <p>Slice 4 只验证 outbox row 如何变成 Kafka command envelope 并交给 KafkaTemplate。
 * 不扫描 outbox,不标记 SENT/RETRY/DEAD,也不接账号上线入口。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProtocolCommandPublisherTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String PARENT_TRACE_ID = "11111111111111111111111111111111";

    @Mock
    private KafkaTemplate<String, ProtocolCommandEnvelope> kafkaTemplate;

    @Mock
    private AccountCredentialMapper credentialMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private IpProxyMapper ipProxyMapper;

    private ProtocolCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = publisherWithMaxInFlight(ProtocolCommandPublisherProperties.DEFAULT_MAX_IN_FLIGHT);
    }

    @Test
    void publish_validOutboxRow_sendsCommandEnvelopeToConfiguredTopicAndKey() {
        ProtocolCommandOutbox row = passthroughOutboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                + "\"source\":\"scheduled_account_group_sync\"}");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProtocolCommandPublishResult result = publisher.publish(row);

        assertThat(result.commandId()).isEqualTo("cmd_100");
        assertThat(result.topic()).isEqualTo("protocol.master.commands.v1");
        assertThat(result.kafkaKey()).isEqualTo("acc_100");

        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, ProtocolCommandEnvelope> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("protocol.master.commands.v1");
        assertThat(record.key()).isEqualTo("acc_100");
        ProtocolCommandEnvelope envelope = record.value();
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
    void publish_validTrace_sendsSameTraceInEnvelopeAndKafkaHeader() {
        ProtocolCommandOutbox row = passthroughOutboxRow(
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\"}");
        row.setTraceId(FIXED_TRACE_ID);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(row);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, ProtocolCommandEnvelope> record = captor.getValue();
        assertThat(record.value().traceId()).isEqualTo(FIXED_TRACE_ID);
        assertThat(new String(record.headers().lastHeader(TraceIds.KAFKA_HEADER).value(),
                StandardCharsets.UTF_8)).isEqualTo(FIXED_TRACE_ID);
    }

    @Test
    void publish_legacyNullTrace_derivesStableTraceAcrossRetries() {
        ProtocolCommandOutbox row = passthroughOutboxRow(
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\"}");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(row);
        publisher.publish(row);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(record -> record.value().traceId())
                .containsExactly(
                        TraceIds.stableFrom(row.getCommandId()),
                        TraceIds.stableFrom(row.getCommandId()));
    }

    @Test
    void publish_ackCallbackLogsWithCommandTraceAndRestoresParentScope() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProtocolCommandPublisher.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (TraceContext.Scope ignored = TraceContext.open(PARENT_TRACE_ID)) {
            logger.setLevel(Level.DEBUG);
            ProtocolCommandOutbox row = passthroughOutboxRow(
                    "{\"accountId\":100,\"protocolAccountId\":\"acc_100\"}");
            row.setTraceId(FIXED_TRACE_ID);
            when(kafkaTemplate.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            publisher.publish(row);

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains("协议命令 Kafka 发送成功")
                                .contains("traceId=" + FIXED_TRACE_ID);
                        assertThat(event.getMDCPropertyMap().get(TraceContext.MDC_KEY))
                                .isEqualTo(FIXED_TRACE_ID);
                    });
            assertThat(TraceContext.current()).contains(PARENT_TRACE_ID);
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
        }
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void publish_pullTaskGroupJoinHydratesReferenceBeforeKafkaSend() {
        ProtocolCommandPayloadHydrator hydrator = org.mockito.Mockito.mock(
                ProtocolCommandPayloadHydrator.class);
        ProtocolCommandPublisher hydratedPublisher = publisherWithMaxInFlight(
                ProtocolCommandPublisherProperties.DEFAULT_MAX_IN_FLIGHT,
                List.of(hydrator));
        ProtocolCommandOutbox row = outboxRow(
                "cmd_pull_1", 1L, 601L, "acc_100",
                "{\"tenantId\":1,\"pullTaskId\":9,\"groupExecutionId\":11,"
                        + "\"actionId\":601,\"source\":\"pull_task_manager_join\"}");
        row.setCommandType("group.join.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setKafkaTopic("protocol.master.commands.v1");
        when(hydrator.supports(row)).thenReturn(true);
        when(hydrator.hydrate(eq(row), any())).thenReturn(new ObjectMapper().valueToTree(java.util.Map.ofEntries(
                java.util.Map.entry("tenantId", 1L),
                java.util.Map.entry("pullTaskId", 9L),
                java.util.Map.entry("groupExecutionId", 11L),
                java.util.Map.entry("actionId", 601L),
                java.util.Map.entry("accountId", 100L),
                java.util.Map.entry("protocolAccountId", "acc_100"),
                java.util.Map.entry("wsPhone", "8613800000100"),
                java.util.Map.entry("protocolBackend", "WEB"),
                java.util.Map.entry("inviteCode", "AbCdEfGhIjKlMnOpQrStUv"),
                java.util.Map.entry("attemptNo", 1),
                java.util.Map.entry("source", "pull_task_manager_join"))));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        hydratedPublisher.publish(row);

        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value().payload().get("inviteCode").asText())
                .isEqualTo("AbCdEfGhIjKlMnOpQrStUv");
        assertThat(captor.getValue().value().payload().get("actionId").asLong()).isEqualTo(601L);
        verify(hydrator).hydrate(eq(row), any());
    }

    @Test
    void publish_offlineOutboxRow_sendsOfflineCommandEnvelopeWithoutProxyPayload() {
        ProtocolCommandOutbox row = outboxRow("{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                + "\"source\":\"batch_offline\"}");
        row.setCommandType("account.offline.requested");
        row.setKafkaTopic("protocol.master.commands.v1");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProtocolCommandPublishResult result = publisher.publish(row);

        assertThat(result.commandId()).isEqualTo("cmd_100");
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        ProtocolCommandEnvelope envelope = captor.getValue().value();
        assertThat(envelope.commandType()).isEqualTo("account.offline.requested");
        assertThat(envelope.aggregateType()).isEqualTo("ACCOUNT");
        assertThat(envelope.aggregateId()).isEqualTo(100L);
        assertThat(envelope.protocolAccountId()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("tenantId").asLong()).isEqualTo(1L);
        assertThat(envelope.payload().get("accountId").asLong()).isEqualTo(100L);
        assertThat(envelope.payload().get("protocolAccountId").asText()).isEqualTo("acc_100");
        assertThat(envelope.payload().get("source").asText()).isEqualTo("batch_offline");
        assertThat(envelope.payload().has("proxyId")).isFalse();
        assertThat(envelope.payload().has("credentialFormat")).isFalse();
    }

    @Test
    void publishBatch_onlineRowsHydratesCredentialsAndProxiesWithBulkQueriesBeforeSending() {
        ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(1);
        ProtocolCommandOutbox first = outboxRow(
                "cmd_100",
                1L,
                100L,
                "acc_100",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                        + "\"credentialFormat\":\"BAILEYS_JSON\",\"proxyId\":7,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_100\",\"previousOnlineAttemptId\":\"oa_99\"}");
        ProtocolCommandOutbox second = outboxRow(
                "cmd_101",
                1L,
                101L,
                "acc_101",
                "{\"accountId\":101,\"protocolAccountId\":\"acc_101\","
                        + "\"credentialFormat\":\"PARAMS\",\"proxyId\":8,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_101\",\"previousOnlineAttemptId\":\"oa_100\"}");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(100L, 101L)))
                .thenReturn(List.of(
                        credential(100L, 2, "{\"creds\":{\"noiseKey\":\"n1\"},\"keys\":{}}"),
                        credential(101L, 3, "{\"login\":\"raw\"}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L, 8L)))
                .thenReturn(List.of(
                        proxy(7L, 100L, 2, "proxy-a.internal", 1080, "user-a", "pass_session-Aaa111", "印度"),
                        proxy(8L, 101L, 1, "proxy-b.internal", 8080, "user-b", "pass_session-Bbb222", "新加坡")));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<ProtocolCommandPublishOutcome> outcomes = boundedPublisher.publishBatch(List.of(first, second));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        verify(credentialMapper).selectByTenantAndAccountIds(1L, List.of(100L, 101L));
        verify(ipProxyMapper).selectActiveByTenantAndIds(1L, List.of(7L, 8L));
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate, times(2)).send(captor.capture());
        ProtocolCommandEnvelope firstEnvelope = captor.getAllValues().get(0).value();
        assertThat(firstEnvelope.payload().get("tenantId").asLong()).isEqualTo(1L);
        assertThat(firstEnvelope.payload().get("format").asText()).isEqualTo("baileys_json");
        assertThat(firstEnvelope.payload().get("onlineAttemptId").asText()).isEqualTo("oa_100");
        assertThat(firstEnvelope.payload().get("previousOnlineAttemptId").asText()).isEqualTo("oa_99");
        assertThat(firstEnvelope.payload().get("source").asText()).isEqualTo("batch_online");
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
    void publishBatch_submitsOneWindowBeforeWaitingAndKeepsInputOrder() throws Exception {
        ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(2);
        List<ProtocolCommandOutbox> rows = List.of(
                passthroughOutboxRow("cmd_100", 100L, "acc_100"),
                passthroughOutboxRow("cmd_101", 101L, "acc_101"),
                passthroughOutboxRow("cmd_102", 102L, "acc_102"));
        CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> first = new CompletableFuture<>();
        CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> second = new CompletableFuture<>();
        CompletableFuture<SendResult<String, ProtocolCommandEnvelope>> third = new CompletableFuture<>();
        List<CompletableFuture<SendResult<String, ProtocolCommandEnvelope>>> sendFutures =
                List.of(first, second, third);
        CountDownLatch firstWindowSubmitted = new CountDownLatch(2);
        CountDownLatch thirdSubmitted = new CountDownLatch(1);
        AtomicInteger sendCount = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            int index = sendCount.getAndIncrement();
            if (index < 2) {
                firstWindowSubmitted.countDown();
            } else {
                thirdSubmitted.countDown();
            }
            return sendFutures.get(index);
        });

        CompletableFuture<List<ProtocolCommandPublishOutcome>> publishing =
                CompletableFuture.supplyAsync(() -> boundedPublisher.publishBatch(rows));

        boolean submittedFullWindow = firstWindowSubmitted.await(1, TimeUnit.SECONDS);
        int sendsBeforeAck = sendCount.get();
        second.completeExceptionally(new IllegalStateException("broker unavailable"));
        boolean crossedWindowBeforeFirstCompleted = thirdSubmitted.await(100, TimeUnit.MILLISECONDS);
        first.complete(null);
        boolean submittedNextWindow = thirdSubmitted.await(1, TimeUnit.SECONDS);
        third.complete(null);
        List<ProtocolCommandPublishOutcome> outcomes = publishing.get(1, TimeUnit.SECONDS);

        assertThat(submittedFullWindow).isTrue();
        assertThat(sendsBeforeAck).isEqualTo(2);
        assertThat(crossedWindowBeforeFirstCompleted).isFalse();
        assertThat(submittedNextWindow).isTrue();
        assertThat(outcomes)
                .extracting(outcome -> outcome.row().getCommandId())
                .containsExactly("cmd_100", "cmd_101", "cmd_102");
        assertThat(outcomes)
                .extracting(ProtocolCommandPublishOutcome::succeeded)
                .containsExactly(true, false, true);
        assertThat(outcomes.get(1).error()).isInstanceOf(ProtocolException.class);
    }

    @Test
    void publishBatchByWindow_notifiesCompletedWindowBeforeSubmittingNextWindow() {
        ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(2);
        List<ProtocolCommandOutbox> rows = List.of(
                passthroughOutboxRow("cmd_100", 100L, "acc_100"),
                passthroughOutboxRow("cmd_101", 101L, "acc_101"),
                passthroughOutboxRow("cmd_102", 102L, "acc_102"));
        List<String> events = new ArrayList<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, ProtocolCommandEnvelope> record = invocation.getArgument(0);
            events.add("send:" + record.key());
            return CompletableFuture.completedFuture(null);
        });

        boundedPublisher.publishBatchByWindow(rows, outcomes -> events.add(
                "window:" + outcomes.stream()
                        .map(outcome -> outcome.row().getKafkaKey())
                        .collect(Collectors.joining(","))));

        assertThat(events).containsExactly(
                "send:acc_100",
                "send:acc_101",
                "window:acc_100,acc_101",
                "send:acc_102",
                "window:acc_102");
    }

    @Test
    void publishBatchByWindow_commitsEachWindowImmediatelyBeforeKafkaSend() {
        ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(2);
        List<ProtocolCommandOutbox> rows = List.of(
                passthroughOutboxRow("cmd_100", 100L, "acc_100"),
                passthroughOutboxRow("cmd_101", 101L, "acc_101"),
                passthroughOutboxRow("cmd_102", 102L, "acc_102"));
        List<String> events = new ArrayList<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, ProtocolCommandEnvelope> record = invocation.getArgument(0);
            events.add("send:" + record.key());
            return CompletableFuture.completedFuture(null);
        });

        boundedPublisher.publishBatchByWindow(
                rows,
                window -> {
                    events.add("commit:" + window.stream()
                            .map(ProtocolCommandOutbox::getKafkaKey)
                            .collect(Collectors.joining(",")));
                    return window.size() == 1 ? List.of() : window;
                },
                outcomes -> events.add("complete:" + outcomes.size()));

        assertThat(events).containsExactly(
                "commit:acc_100,acc_101",
                "send:acc_100",
                "send:acc_101",
                "complete:2",
                "commit:acc_102");
        verify(kafkaTemplate, never()).send(
                org.mockito.ArgumentMatchers.<ProducerRecord<String, ProtocolCommandEnvelope>>argThat(
                        record -> "acc_102".equals(record.key())));
    }

    @Test
    void publishBatch_onlineRowWithNullPreviousAttemptKeepsKafkaPayloadJsonNull() {
        ProtocolCommandOutbox row = outboxRow(
                "cmd_100",
                1L,
                100L,
                "acc_100",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                        + "\"credentialFormat\":\"BAILEYS_JSON\",\"proxyId\":7,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_100\",\"previousOnlineAttemptId\":null}");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(100L)))
                .thenReturn(List.of(credential(100L, 2, "{\"creds\":{\"noiseKey\":\"n1\"},\"keys\":{}}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L)))
                .thenReturn(List.of(proxy(7L, 100L, 2, "proxy-a.internal", 1080,
                        "user-a", "pass_session-Aaa111", "印度")));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<ProtocolCommandPublishOutcome> outcomes = publisher.publishBatch(List.of(row));

        assertThat(outcomes).singleElement().satisfies(outcome -> assertThat(outcome.succeeded()).isTrue());
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value().payload().has("previousOnlineAttemptId")).isTrue();
        assertThat(captor.getValue().value().payload().get("previousOnlineAttemptId").isNull()).isTrue();
    }

    @Test
    void publishBatch_onlineAndroidRowKeepsProtocolBackendInKafkaPayload() {
        ProtocolCommandOutbox row = outboxRow(
                "cmd_android",
                1L,
                100L,
                "acc_100",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                        + "\"credentialFormat\":\"SIX_SEGMENT\",\"proxyId\":7,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_100\",\"protocolBackend\":\"ANDROID\"}");
        row.setKafkaTopic("protocol.android.commands.v1");
        row.setProtocolBackend("ANDROID");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(100L)))
                .thenReturn(List.of(credential(100L, 1, "{\"segments\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L)))
                .thenReturn(List.of(proxy(7L, 100L, 2, "proxy-a.internal", 1080,
                        "user-a", "pass_session-Aaa111", "印度")));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<ProtocolCommandPublishOutcome> outcomes = publisher.publishBatch(List.of(row));

        assertThat(outcomes).singleElement().satisfies(outcome -> assertThat(outcome.succeeded()).isTrue());
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value().payload().get("protocolBackend").asText()).isEqualTo("ANDROID");
    }

    @Test
    void publishBatch_onlineAndroidRowBuildsZhuanLifecyclePayload() {
        ProtocolCommandOutbox row = outboxRow(
                "cmd_android",
                1L,
                100L,
                "acc_919000000001",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_919000000001\","
                        + "\"credentialFormat\":\"SIX_SEGMENT\",\"proxyId\":7,\"isBusiness\":true,"
                        + "\"source\":\"batch_online\",\"onlineAttemptId\":\"oa_android\","
                        + "\"previousOnlineAttemptId\":\"oa_previous\",\"protocolBackend\":\"ANDROID\"}");
        row.setKafkaTopic("protocol.android.commands.v1");
        row.setProtocolBackend("ANDROID");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(100L)))
                .thenReturn(List.of(credential(100L, 1, "{\"phone\":\"919000000001\","
                        + "\"static_pub_key\":\"static-pub\",\"static_pri_key\":\"static-pri\","
                        + "\"id_pub_key\":\"identity-pub\",\"id_pri_key\":\"identity-pri\","
                        + "\"phone_id\":\"phone-id\"}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L)))
                .thenReturn(List.of(proxy(7L, 100L, 2, "proxy-a.internal", 1080,
                        "user-a", "pass_session-Aaa111", "印度")));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<ProtocolCommandPublishOutcome> outcomes = publisher.publishBatch(List.of(row));

        assertThat(outcomes).singleElement().satisfies(outcome -> assertThat(outcome.succeeded()).isTrue());
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        ProtocolCommandEnvelope envelope = captor.getValue().value();
        assertThat(envelope.payload().get("tenantId").asLong()).isEqualTo(1L);
        assertThat(envelope.payload().get("accountId").asLong()).isEqualTo(100L);
        assertThat(envelope.payload().get("protocolAccountId").asText()).isEqualTo("acc_919000000001");
        assertThat(envelope.payload().get("format").asText()).isEqualTo("six");
        assertThat(envelope.payload().path("isBusiness").asBoolean()).isTrue();
        assertThat(envelope.payload().get("credential").get("static_pub_key").asText()).isEqualTo("static-pub");
        assertThat(envelope.payload().get("credential").get("phone_id").asText()).isEqualTo("phone-id");
        assertThat(envelope.payload().get("proxy").get("protocol").asText()).isEqualTo("socks5");
        assertThat(envelope.payload().get("proxy").get("url").asText())
                .isEqualTo("socks5://user-a:pass_session-Aaa111@proxy-a.internal:1080");
        assertThat(envelope.payload().get("source").asText()).isEqualTo("batch_online");
        assertThat(envelope.payload().get("onlineAttemptId").asText()).isEqualTo("oa_android");
        assertThat(envelope.payload().get("previousOnlineAttemptId").asText()).isEqualTo("oa_previous");
        assertThat(envelope.payload().get("protocolBackend").asText()).isEqualTo("ANDROID");
    }

    @Test
    void publishBatch_onlineRowWithNullOnlineAttemptReturnsValidationFailureWithoutSendingKafka() {
        ProtocolCommandOutbox row = outboxRow(
                "cmd_100",
                1L,
                100L,
                "acc_100",
                "{\"accountId\":100,\"protocolAccountId\":\"acc_100\","
                        + "\"credentialFormat\":\"BAILEYS_JSON\",\"proxyId\":7,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":null,\"previousOnlineAttemptId\":\"oa_99\"}");

        List<ProtocolCommandPublishOutcome> outcomes = publisher.publishBatch(List.of(row));

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.succeeded()).isFalse();
            assertThat(outcome.error())
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("payload 缺少字段 onlineAttemptId");
        });
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
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
            when(kafkaTemplate.send(any(ProducerRecord.class)))
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
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(row))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("协议命令 Kafka 发送失败")
                .hasMessageContaining("cmd_100")
                .hasMessageNotContaining("protocolAccountId")
                .hasMessageNotContaining("scheduled_account_group_sync");
    }

    @Test
    void maximumWindowSendDuration_coversEachSynchronousSendAndAsyncAckTimeout() {
        @SuppressWarnings("unchecked")
        ProducerFactory<String, ProtocolCommandEnvelope> producerFactory =
                org.mockito.Mockito.mock(ProducerFactory.class);
        when(kafkaTemplate.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties()).thenReturn(
                Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "12000"));
        ProtocolCommandPublisher boundedPublisher = publisherWithMaxInFlight(3);

        assertThat(boundedPublisher.maximumWindowSendDurationMs()).isEqualTo(41_000L);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> producerRecordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
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

    private static ProtocolCommandOutbox passthroughOutboxRow(String commandId,
                                                              Long accountId,
                                                              String protocolAccountId) {
        ProtocolCommandOutbox row = outboxRow(
                commandId,
                1L,
                accountId,
                protocolAccountId,
                "{\"accountId\":" + accountId + ",\"protocolAccountId\":\"" + protocolAccountId
                        + "\",\"source\":\"scheduled_account_group_sync\"}");
        row.setCommandType("account.groups_sync.requested");
        row.setKafkaTopic("protocol.master.commands.v1");
        return row;
    }

    @Test
    @DisplayName("上线命令按账号群基线状态下发 groupBaselineReady")
    void onlineCommandCarriesGroupBaselineReady() {
        // Arrange：PENDING 与缺失行都必须判为未就绪，CAPTURED/DISABLED 判为已就绪。
        ProtocolCommandOutbox captured = outboxRow(
                "cmd_200", 1L, 200L, "acc_200",
                "{\"accountId\":200,\"protocolAccountId\":\"acc_200\","
                        + "\"credentialFormat\":\"PARAMS\",\"proxyId\":7,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_200\"}");
        ProtocolCommandOutbox pending = outboxRow(
                "cmd_201", 1L, 201L, "acc_201",
                "{\"accountId\":201,\"protocolAccountId\":\"acc_201\","
                        + "\"credentialFormat\":\"PARAMS\",\"proxyId\":8,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_201\"}");
        ProtocolCommandOutbox missing = outboxRow(
                "cmd_202", 1L, 202L, "acc_202",
                "{\"accountId\":202,\"protocolAccountId\":\"acc_202\","
                        + "\"credentialFormat\":\"PARAMS\",\"proxyId\":9,\"source\":\"batch_online\","
                        + "\"onlineAttemptId\":\"oa_202\"}");
        when(credentialMapper.selectByTenantAndAccountIds(1L, List.of(200L, 201L, 202L)))
                .thenReturn(List.of(
                        credential(200L, 3, "{\"login\":\"raw\"}"),
                        credential(201L, 3, "{\"login\":\"raw\"}"),
                        credential(202L, 3, "{\"login\":\"raw\"}")));
        when(ipProxyMapper.selectActiveByTenantAndIds(1L, List.of(7L, 8L, 9L)))
                .thenReturn(List.of(
                        proxy(7L, 200L, 1, "proxy-a.internal", 1080, "user-a", "pass_session-Aaa111", "印度"),
                        proxy(8L, 201L, 1, "proxy-b.internal", 1080, "user-b", "pass_session-Bbb222", "印度"),
                        proxy(9L, 202L, 1, "proxy-c.internal", 1080, "user-c", "pass_session-Ccc333", "印度")));
        when(accountMapper.selectGroupBaselineStatesByTenantAndAccountIds(1L, List.of(200L, 201L, 202L)))
                .thenReturn(List.of(
                        new AccountGroupBaselineStateRow(
                                200L, AccountGroupBaselineStateCode.CAPTURED, null),
                        new AccountGroupBaselineStateRow(
                                201L, AccountGroupBaselineStateCode.PENDING, null)));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        List<ProtocolCommandPublishOutcome> outcomes =
                publisherWithMaxInFlight(8).publishBatch(List.of(captured, pending, missing));

        // Assert
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        ArgumentCaptor<ProducerRecord<String, ProtocolCommandEnvelope>> captor = producerRecordCaptor();
        verify(kafkaTemplate, times(3)).send(captor.capture());
        Map<String, Boolean> baselineReadyByProtocolAccountId = captor.getAllValues().stream()
                .collect(Collectors.toMap(
                        ProducerRecord::key,
                        record -> record.value().payload().get("groupBaselineReady").asBoolean()));
        assertThat(baselineReadyByProtocolAccountId.get("acc_200")).isTrue();
        assertThat(baselineReadyByProtocolAccountId.get("acc_201")).isFalse();
        // 查不到基线行时必须保守判为未就绪，让协议层退化为读取成员明细。
        assertThat(baselineReadyByProtocolAccountId.get("acc_202")).isFalse();
    }

    private ProtocolCommandPublisher publisherWithMaxInFlight(int maxInFlight) {
        return publisherWithMaxInFlight(maxInFlight, List.of());
    }

    private ProtocolCommandPublisher publisherWithMaxInFlight(
            int maxInFlight,
            List<ProtocolCommandPayloadHydrator> hydrators) {
        ProtocolCommandPublisherProperties properties = new ProtocolCommandPublisherProperties();
        properties.setSendTimeoutMs(5_000);
        properties.setMaxInFlight(maxInFlight);
        return new ProtocolCommandPublisher(
                kafkaTemplate,
                new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL),
                properties,
                credentialMapper,
                accountMapper,
                ipProxyMapper,
                new ProxyResolver(),
                hydrators);
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
