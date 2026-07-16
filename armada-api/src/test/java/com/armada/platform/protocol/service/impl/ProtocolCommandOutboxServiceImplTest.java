package com.armada.platform.protocol.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 协议命令 Outbox enqueue service 单测。
 *
 * <p>Slice 3 只验证上线命令如何转成 outbox row。不发送 Kafka,不接账号上线入口。</p>
 */
class ProtocolCommandOutboxServiceImplTest {

    private final ProtocolCommandOutboxMapper mapper = org.mockito.Mockito.mock(ProtocolCommandOutboxMapper.class);
    private final ProtocolCommandDispatchTrigger dispatchTrigger =
            org.mockito.Mockito.mock(ProtocolCommandDispatchTrigger.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void enqueueGroupJoinCommands_routesWebAndAndroidWithApprovedPayload() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-web", "cmd-android"), List.of(),
                ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                "protocol.master.commands.test",
                "protocol.android.commands.test");
        when(mapper.batchInsertPending(anyList())).thenReturn(2);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueGroupJoinCommands(List.of(
                groupJoinCommand(ProtocolBackend.WEB, 26L, 382L, "acc-web", "911", "WEB-CODE"),
                groupJoinCommand(ProtocolBackend.ANDROID, 27L, 383L, "acc-android", "922", "ANDROID-CODE")));

        assertThat(result.batchId()).isEqualTo("join-task:9");
        assertThat(result.commandIds()).containsExactly("cmd-web", "cmd-android");
        assertThat(result.inserted()).isEqualTo(2);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                .containsExactly("protocol.master.commands.test", "protocol.android.commands.test");
        assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaKey)
                .containsExactly("acc-web", "acc-android");
        assertThat(rows).extracting(ProtocolCommandOutbox::getBatchId)
                .containsOnly("join-task:9");
        assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateType)
                .containsOnly("JOIN_TASK_RESULT");
        assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateId)
                .containsExactly(26L, 27L);
        assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                .containsOnly("group.join.requested");

        Map<String, Object> payload = objectMapper.readValue(rows.get(1).getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenantId", 1,
                "joinTaskId", 9,
                "joinTaskResultId", 27,
                "accountId", 383,
                "protocolAccountId", "acc-android",
                "wsPhone", "922",
                "protocolBackend", "ANDROID",
                "inviteCode", "ANDROID-CODE",
                "attemptNo", 1,
                "source", "join_task"));
    }

    @Test
    void enqueueOnlineCommands_singleCommand_insertsPendingRowWithStableEnvelopeAndSafePayload() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-single"), List.of());
        ProtocolOnlineCommandRequest command = onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOnlineCommands(List.of(command));

        assertThat(result.batchId()).isNull();
        assertThat(result.commandIds()).containsExactly("cmd-single");
        assertThat(result.inserted()).isEqualTo(1);

        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(1);
        ProtocolCommandOutbox row = rows.get(0);
        assertThat(row.getCommandId()).isEqualTo("cmd-single");
        assertThat(row.getBatchId()).isNull();
        assertThat(row.getCommandType()).isEqualTo("account.online.requested");
        assertThat(row.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(row.getAggregateId()).isEqualTo(100L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.account.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_100");
        assertThat(row.getProtocolAccountId()).isEqualTo("acc_100");
        assertThat(row.getProtocolBackend()).isEqualTo("WEB");
        assertThat(row.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getNextRetryAt()).isZero();
        assertThat(row.getCreatedAt()).isEqualTo(row.getUpdatedAt());
        assertThat(row.getCreatedAt()).isPositive();

        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("accountId", 100)
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("credentialFormat", "BAILEYS_JSON")
                .containsEntry("proxyId", 7)
                .containsEntry("source", "manual_online")
                .containsEntry("onlineAttemptId", "oa_100")
                .containsEntry("previousOnlineAttemptId", "oa_99")
                .containsEntry("protocolBackend", "WEB");
        assertThat(row.getPayloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("creds")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOnlineCommands_customCommandTopic_usesConfiguredTopic() {
        TestableProtocolCommandOutboxService service =
                newService(List.of("cmd-custom-topic"), List.of(), "protocol.account.commands.test",
                        ProtocolMasterCommandProperties.DEFAULT_TOPIC);
        ProtocolOnlineCommandRequest command = onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueOnlineCommands(List.of(command));

        assertThat(capturedRows()).extracting(ProtocolCommandOutbox::getKafkaTopic)
                .containsExactly("protocol.account.commands.test");
    }

    @Test
    void enqueueOnlineCommands_androidBackend_usesAndroidTopicAndPersistsBackendInSafePayload() throws Exception {
        TestableProtocolCommandOutboxService service =
                newService(List.of("cmd-android"), List.of(), ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                        ProtocolMasterCommandProperties.DEFAULT_TOPIC, "protocol.android.commands.test");
        ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                100L,
                "acc_100",
                CredentialFormat.BAILEYS_JSON,
                7L,
                "manual_online",
                "oa_100",
                "oa_99",
                ProtocolBackend.ANDROID,
                true);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueOnlineCommands(List.of(command));

        ProtocolCommandOutbox row = capturedRows().get(0);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.commands.test");
        assertThat(row.getProtocolBackend()).isEqualTo(ProtocolBackend.ANDROID.name());
        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("protocolBackend", "ANDROID")
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("onlineAttemptId", "oa_100")
                .containsEntry("isBusiness", true)
                .doesNotContainKeys("credential", "sixdata", "proxy", "proxyAddress", "proxyDetails");
    }

    @Test
    void enqueueOnlineCommands_nullPreviousAttemptKeepsPayloadFieldWithJsonNull() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-null-previous"), List.of());
        ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                100L,
                "acc_100",
                CredentialFormat.BAILEYS_JSON,
                7L,
                "manual_online",
                "oa_100",
                null);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueOnlineCommands(List.of(command));

        JsonNode payload = objectMapper.readTree(capturedRows().get(0).getPayloadJson());
        assertThat(payload.has("previousOnlineAttemptId")).isTrue();
        assertThat(payload.get("previousOnlineAttemptId").isNull()).isTrue();
    }

    @Test
    void enqueueOnlineCommands_copiesTenantContextToInMemoryRowsForAfterCommitHydration() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-tenant"), List.of());
        ProtocolOnlineCommandRequest command = onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);
        TenantContext.set(88L);
        try {
            service.enqueueOnlineCommands(List.of(command));
        } finally {
            TenantContext.clear();
        }

        assertThat(capturedRows()).extracting(ProtocolCommandOutbox::getTenantId)
                .containsExactly(88L);
    }

    @Test
    void enqueueOnlineCommands_batch500_usesOneBatchIdAndOneRowPerCommand() {
        List<String> commandIds = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "cmd-" + i)
                .toList();
        TestableProtocolCommandOutboxService service = newService(commandIds, List.of("batch-1"));
        List<ProtocolOnlineCommandRequest> commands = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> onlineCommand((long) i, "acc_" + i, CredentialFormat.PARAMS, 7000L + i))
                .toList();
        when(mapper.batchInsertPending(anyList())).thenReturn(500);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOnlineCommands(commands);

        assertThat(result.batchId()).isEqualTo("batch-1");
        assertThat(result.commandIds()).containsExactlyElementsOf(commandIds);
        assertThat(result.inserted()).isEqualTo(500);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(500);
        assertThat(rows).allSatisfy(row -> assertThat(row.getBatchId()).isEqualTo("batch-1"));
        assertThat(rows).extracting(ProtocolCommandOutbox::getCommandId)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(commandIds);
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOfflineCommands_batch500_usesOneBatchIdAndSafePayload() throws Exception {
        List<String> commandIds = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "cmd-offline-" + i)
                .toList();
        TestableProtocolCommandOutboxService service = newService(commandIds, List.of("batch-offline-1"));
        List<ProtocolOfflineCommandRequest> commands = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> offlineCommand((long) i, "acc_" + i))
                .toList();
        when(mapper.batchInsertPending(anyList())).thenReturn(500);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOfflineCommands(commands);

        assertThat(result.batchId()).isEqualTo("batch-offline-1");
        assertThat(result.commandIds()).containsExactlyElementsOf(commandIds);
        assertThat(result.inserted()).isEqualTo(500);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(500);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getBatchId()).isEqualTo("batch-offline-1");
            assertThat(row.getCommandType()).isEqualTo("account.offline.requested");
            assertThat(row.getAggregateType()).isEqualTo("ACCOUNT");
            assertThat(row.getKafkaTopic()).isEqualTo("protocol.master.commands.v1");
            assertThat(row.getKafkaKey()).startsWith("acc_");
            assertThat(row.getProtocolAccountId()).startsWith("acc_");
            assertThat(row.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
            assertThat(row.getRetryCount()).isZero();
            assertThat(row.getNextRetryAt()).isZero();
        });
        assertThat(rows).extracting(ProtocolCommandOutbox::getCommandId)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(commandIds);

        ProtocolCommandOutbox first = rows.get(0);
        Map<String, Object> payload = objectMapper.readValue(first.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("accountId", 1)
                .containsEntry("protocolAccountId", "acc_1")
                .containsEntry("source", "batch_offline");
        assertThat(first.getPayloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("credentialFormat")
                .doesNotContain("proxyId")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOfflineCommands_batch1000_usesOneBatchIdAndOneRowPerCommand() {
        List<String> commandIds = java.util.stream.IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> "cmd-offline-1000-" + i)
                .toList();
        TestableProtocolCommandOutboxService service = newService(commandIds, List.of("batch-offline-1000"));
        List<ProtocolOfflineCommandRequest> commands = java.util.stream.IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> offlineCommand((long) i, "acc_" + i))
                .toList();
        when(mapper.batchInsertPending(anyList())).thenReturn(1000);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOfflineCommands(commands);

        assertThat(result.batchId()).isEqualTo("batch-offline-1000");
        assertThat(result.commandIds()).containsExactlyElementsOf(commandIds);
        assertThat(result.inserted()).isEqualTo(1000);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(1000);
        assertThat(rows).allSatisfy(row -> assertThat(row.getBatchId()).isEqualTo("batch-offline-1000"));
        assertThat(rows).extracting(ProtocolCommandOutbox::getProtocolBackend)
                .containsOnly(ProtocolBackend.WEB.name());
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOfflineCommands_androidBackend_usesAndroidTopicAndPersistsBackendInSafePayload() throws Exception {
        TestableProtocolCommandOutboxService service =
                newService(List.of("cmd-android-offline"), List.of(), ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                        ProtocolMasterCommandProperties.DEFAULT_TOPIC, "protocol.android.commands.test");
        ProtocolOfflineCommandRequest command = new ProtocolOfflineCommandRequest(
                100L,
                "acc_100",
                "batch_offline",
                ProtocolBackend.ANDROID);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueOfflineCommands(List.of(command));

        ProtocolCommandOutbox row = capturedRows().get(0);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.commands.test");
        assertThat(row.getProtocolBackend()).isEqualTo(ProtocolBackend.ANDROID.name());
        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("protocolBackend", "ANDROID")
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("source", "batch_offline");
    }

    @Test
    void enqueueGroupHealthCheckCommands_batch501_keepsNonLifecycleLimitAt500() {
        TestableProtocolCommandOutboxService service = newService(List.of(), List.of());
        List<ProtocolGroupHealthCheckCommandRequest> commands = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(i -> groupHealthCommand(1L, (long) i, "1203630" + i + "@g.us",
                        1000L + i, "acc_" + i))
                .toList();

        assertThatThrownBy(() -> service.enqueueGroupHealthCheckCommands(commands))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群链接健康检查命令不能超过 500 条")
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION.code());
        verify(mapper, never()).batchInsertPending(anyList());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueGroupHealthCheckCommands_singleCommand_insertsGroupLinkCommandWithRoutablePayload() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-group-health"), List.of());
        ProtocolGroupHealthCheckCommandRequest command = groupHealthCommand(
                1L, 200L, "120363000000000000@g.us", 100L, "acc_100");
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueGroupHealthCheckCommands(List.of(command));

        assertThat(result.batchId()).isNull();
        assertThat(result.commandIds()).containsExactly("cmd-group-health");
        assertThat(result.inserted()).isEqualTo(1);

        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(1);
        ProtocolCommandOutbox row = rows.get(0);
        assertThat(row.getCommandId()).isEqualTo("cmd-group-health");
        assertThat(row.getBatchId()).isNull();
        assertThat(row.getCommandType()).isEqualTo("group.health_check.requested");
        assertThat(row.getAggregateType()).isEqualTo("GROUP_LINK");
        assertThat(row.getAggregateId()).isEqualTo(200L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.master.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_100");
        assertThat(row.getProtocolAccountId()).isEqualTo("acc_100");
        assertThat(row.getProtocolBackend()).isEqualTo("WEB");
        assertThat(row.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getNextRetryAt()).isZero();

        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("tenantId", 1)
                .containsEntry("groupLinkId", 200)
                .containsEntry("groupJid", "120363000000000000@g.us")
                .containsEntry("accountId", 100)
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("source", "scheduled_group_link_health");
        assertThat(row.getPayloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("creds")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueGroupHealthCheckCommands_customMasterTopic_usesConfiguredMasterTopic() {
        TestableProtocolCommandOutboxService service =
                newService(List.of("cmd-group-health-custom-topic"), List.of(),
                        ProtocolAccountCommandProperties.DEFAULT_TOPIC, "protocol.master.commands.test");
        ProtocolGroupHealthCheckCommandRequest command = groupHealthCommand(
                1L, 200L, "120363000000000000@g.us", 100L, "acc_100");
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueGroupHealthCheckCommands(List.of(command));

        assertThat(capturedRows()).extracting(ProtocolCommandOutbox::getKafkaTopic)
                .containsExactly("protocol.master.commands.test");
    }

    @Test
    void enqueueAccountGroupSyncCommands_singleCommand_insertsMasterRoutedAccountCommand() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-account-groups"), List.of());
        ProtocolAccountGroupSyncCommandRequest command = accountGroupSyncCommand(1L, 100L, "acc_100");
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueAccountGroupSyncCommands(List.of(command));

        assertThat(result.batchId()).isNull();
        assertThat(result.commandIds()).containsExactly("cmd-account-groups");
        assertThat(result.inserted()).isEqualTo(1);

        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(1);
        ProtocolCommandOutbox row = rows.get(0);
        assertThat(row.getCommandId()).isEqualTo("cmd-account-groups");
        assertThat(row.getBatchId()).isNull();
        assertThat(row.getCommandType()).isEqualTo("account.groups_sync.requested");
        assertThat(row.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(row.getAggregateId()).isEqualTo(100L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.master.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_100");
        assertThat(row.getProtocolAccountId()).isEqualTo("acc_100");
        assertThat(row.getProtocolBackend()).isEqualTo("WEB");
        assertThat(row.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getNextRetryAt()).isZero();

        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("tenantId", 1)
                .containsEntry("accountId", 100)
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("source", "scheduled_account_group_sync");
        assertThat(row.getPayloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("creds")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueMessageCommands_persistsBackendEncodedEnvelope() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of(), List.of());
        MessageSendCommand command = new MessageSendCommand(
                new ProtocolAccountRef(501L, ProtocolBackend.ANDROID, "acc_android", "919000000001"),
                new MessageSendCommand.MessageTarget("120363001@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("hello", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        1L,
                        "marketing_task",
                        new MessageSendCommand.MarketingCorrelation(42L, 7001L, 9001L, 1L),
                        null),
                "cmd_android");
        ProtocolMessageOutboxCommand outboxCommand = new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.ANDROID,
                "protocol.android.commands.v1",
                "acc_android",
                Map.ofEntries(
                        Map.entry("tenantId", 1L),
                        Map.entry("accountId", 501L),
                        Map.entry("protocolAccountId", "acc_android"),
                        Map.entry("wsPhone", "919000000001"),
                        Map.entry("groupJid", "120363001@g.us"),
                        Map.entry("messageType", "TEXT"),
                        Map.entry("text", "hello"),
                        Map.entry("mentionAll", false),
                        Map.entry("source", "marketing_task"),
                        Map.entry("marketingTaskId", 42L),
                        Map.entry("targetId", 7001L),
                        Map.entry("attemptId", 9001L),
                        Map.entry("roundNo", 1L)));
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueMessageCommands(List.of(outboxCommand));

        assertThat(result.commandIds()).containsExactly("cmd_android");
        ProtocolCommandOutbox row = capturedRows().get(0);
        assertThat(row.getAggregateType()).isEqualTo("MARKETING_SEND_ATTEMPT");
        assertThat(row.getAggregateId()).isEqualTo(9001L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_android");
        assertThat(row.getProtocolBackend()).isEqualTo("ANDROID");
        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("wsPhone", "919000000001")
                .containsEntry("messageType", "TEXT");
    }

    @Test
    void enqueueOnlineCommands_duplicateGeneratedCommandId_throwsConflictBeforeMapperInsert() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-dupe", "cmd-dupe"), List.of("batch-1"));
        List<ProtocolOnlineCommandRequest> commands = List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L),
                onlineCommand(101L, "acc_101", CredentialFormat.BAILEYS_JSON, 8L));

        assertThatThrownBy(() -> service.enqueueOnlineCommands(commands))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 ID 重复")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(mapper, never()).batchInsertPending(anyList());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueOnlineCommands_mapperDuplicateCommandId_mapsToBusinessConflict() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-existing"), List.of());
        when(mapper.batchInsertPending(anyList())).thenThrow(new DuplicateKeyException("uk_command_id"));

        assertThatThrownBy(() -> service.enqueueOnlineCommands(List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 ID 已存在")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueOnlineCommands_missingOnlineAttemptId_throwsValidationBeforeInsert() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-a"), List.of());
        ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                100L,
                "acc_100",
                CredentialFormat.BAILEYS_JSON,
                7L,
                "manual_online",
                null,
                "oa_99");

        assertThatThrownBy(() -> service.enqueueOnlineCommands(List.of(command)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议上线命令缺少 onlineAttemptId")
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION.code());
        verify(mapper, never()).batchInsertPending(anyList());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueOnlineCommands_insertedCountMismatch_throwsConflictBeforeDispatch() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-a", "cmd-b"), List.of("batch-1"));
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        assertThatThrownBy(() -> service.enqueueOnlineCommands(List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L),
                onlineCommand(101L, "acc_101", CredentialFormat.PARAMS, 8L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 outbox 写入数量不一致")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds, List<String> batchIds) {
        return newService(commandIds, batchIds, ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                ProtocolMasterCommandProperties.DEFAULT_TOPIC, ProtocolAndroidCommandProperties.DEFAULT_TOPIC);
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds,
                                                            List<String> batchIds,
                                                            String accountCommandTopic,
                                                            String masterCommandTopic) {
        return newService(commandIds, batchIds, accountCommandTopic, masterCommandTopic,
                ProtocolAndroidCommandProperties.DEFAULT_TOPIC);
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds,
                                                            List<String> batchIds,
                                                            String accountCommandTopic,
                                                            String masterCommandTopic,
                                                            String androidCommandTopic) {
        ProtocolAccountCommandProperties accountProperties = new ProtocolAccountCommandProperties();
        accountProperties.setTopic(accountCommandTopic);
        ProtocolMasterCommandProperties masterProperties = new ProtocolMasterCommandProperties();
        masterProperties.setTopic(masterCommandTopic);
        ProtocolAndroidCommandProperties androidProperties = new ProtocolAndroidCommandProperties();
        androidProperties.setTopic(androidCommandTopic);
        return new TestableProtocolCommandOutboxService(mapper, objectMapper, dispatchTrigger, accountProperties,
                masterProperties, androidProperties, commandIds, batchIds);
    }

    private List<ProtocolCommandOutbox> capturedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolCommandOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsertPending(captor.capture());
        return captor.getValue();
    }

    private static ProtocolOnlineCommandRequest onlineCommand(Long accountId,
                                                              String protocolAccountId,
                                                              CredentialFormat credentialFormat,
                                                              Long proxyId) {
        return new ProtocolOnlineCommandRequest(
                accountId,
                protocolAccountId,
                credentialFormat,
                proxyId,
                "manual_online",
                "oa_100",
                "oa_99");
    }

    private static ProtocolOfflineCommandRequest offlineCommand(Long accountId, String protocolAccountId) {
        return new ProtocolOfflineCommandRequest(
                accountId,
                protocolAccountId,
                "batch_offline");
    }

    private static ProtocolGroupHealthCheckCommandRequest groupHealthCommand(Long tenantId,
                                                                             Long groupLinkId,
                                                                             String groupJid,
                                                                             Long accountId,
                                                                             String protocolAccountId) {
        return new ProtocolGroupHealthCheckCommandRequest(
                tenantId,
                groupLinkId,
                groupJid,
                accountId,
                protocolAccountId,
                "scheduled_group_link_health");
    }

    private static ProtocolAccountGroupSyncCommandRequest accountGroupSyncCommand(Long tenantId,
                                                                                  Long accountId,
                                                                                  String protocolAccountId) {
        return new ProtocolAccountGroupSyncCommandRequest(
                tenantId,
                accountId,
                protocolAccountId,
                "scheduled_account_group_sync");
    }

    private static ProtocolGroupJoinCommandRequest groupJoinCommand(
            ProtocolBackend backend,
            Long resultId,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String inviteCode) {
        return new ProtocolGroupJoinCommandRequest(
                1L, 9L, resultId, accountId, protocolAccountId, wsPhone,
                backend, inviteCode, 1, "join_task");
    }

    private static final class TestableProtocolCommandOutboxService extends ProtocolCommandOutboxServiceImpl {

        private final ArrayDeque<String> commandIds;
        private final ArrayDeque<String> batchIds;

        private TestableProtocolCommandOutboxService(ProtocolCommandOutboxMapper mapper,
                                                     ObjectMapper objectMapper,
                                                     ProtocolCommandDispatchTrigger dispatchTrigger,
                                                     ProtocolAccountCommandProperties accountProperties,
                                                     ProtocolMasterCommandProperties masterProperties,
                                                     ProtocolAndroidCommandProperties androidProperties,
                                                     List<String> commandIds,
                                                     List<String> batchIds) {
            super(mapper, objectMapper, dispatchTrigger, accountProperties, masterProperties, androidProperties);
            this.commandIds = new ArrayDeque<>(commandIds);
            this.batchIds = new ArrayDeque<>(batchIds);
        }

        @Override
        protected String newCommandId() {
            return commandIds.removeFirst();
        }

        @Override
        protected String newBatchId() {
            return batchIds.removeFirst();
        }
    }
}
