package com.armada.platform.protocol.service.impl;

import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskManagerAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskPullerInviteCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
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

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

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
                ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                "protocol.android.group-join.commands.test");
        when(mapper.batchInsertPending(anyList())).thenReturn(2);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueGroupJoinCommands(List.of(
                groupJoinCommand(ProtocolBackend.WEB, 26L, 382L, "acc-web", "911", "WEB-CODE"),
                groupJoinCommand(ProtocolBackend.ANDROID, 27L, 383L, "acc-android", "922", "ANDROID-CODE")));

        assertThat(result.batchId()).isEqualTo("join-task:9");
        assertThat(result.commandIds()).containsExactly("cmd-web", "cmd-android");
        assertThat(result.inserted()).isEqualTo(2);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                .containsExactly("protocol.master.commands.test", "protocol.android.group-join.commands.test");
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
    void enqueuePullTaskGroupJoinCommands_persistsReferencesAndRoutesByFrozenBackend() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-pull-web"), List.of(),
                ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                "protocol.master.commands.test",
                ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result = service.enqueuePullTaskGroupJoinCommands(List.of(
                    new ProtocolPullTaskGroupJoinCommandRequest(
                            1L, 9L, 11L, 601L,
                            new ProtocolAccountRef(
                                    382L, ProtocolBackend.WEB, "acc-web", "911"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            assertThat(result.commandIds()).containsExactly("cmd-pull-web");
            ProtocolCommandOutbox row = capturedRows().get(0);
            assertThat(row.getAggregateType()).isEqualTo("PULL_TASK_ACCOUNT_ACTION");
            assertThat(row.getAggregateId()).isEqualTo(601L);
            assertThat(row.getKafkaTopic()).isEqualTo("protocol.master.commands.test");
            assertThat(row.getKafkaKey()).isEqualTo("acc-web");
            assertThat(row.getProtocolAccountId()).isEqualTo("acc-web");
            assertThat(row.getProtocolBackend()).isEqualTo("WEB");
            assertThat(row.getCommandType()).isEqualTo("group.join.requested");

            Map<String, Object> payload = objectMapper.readValue(
                    row.getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "actionId", 601,
                    "source", "pull_task_manager_join"));
            assertThat(row.getPayloadJson())
                    .doesNotContain("inviteCode")
                    .doesNotContain("wsPhone")
                    .doesNotContain("accountId")
                    .doesNotContain("credential");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskContactSaveCommandsPersistsOnlyReferencesAndRoutesByBackend() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-contact-web", "cmd-contact-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskContactSaveCommands(List.of(
                            new ProtocolPullTaskContactSaveCommandRequest(
                                    1L, 9L, 11L, 601L,
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB, "acc-web", "911")),
                            new ProtocolPullTaskContactSaveCommandRequest(
                                    1L, 9L, 11L, 602L,
                                    new ProtocolAccountRef(
                                            383L, ProtocolBackend.ANDROID, "acc-android", "922"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("contact.save.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateId)
                    .containsExactly(601L, 602L);
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                            ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            Map<String, Object> payload = objectMapper.readValue(
                    rows.get(0).getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "actionId", 601,
                    "source", "pull_task_contact_save"));
            assertThat(rows.get(0).getPayloadJson())
                    .doesNotContain("wsPhone")
                    .doesNotContain("\"contact\":")
                    .doesNotContain("accountId");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskMemberQueryCommandsPersistsReferencesAndReusesExistingTopics()
            throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-query-web", "cmd-query-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskMemberQueryCommands(List.of(
                            new ProtocolPullTaskMemberQueryCommandRequest(
                                    1L, 9L, 11L, 701L,
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB, "acc-web", "911")),
                            new ProtocolPullTaskMemberQueryCommandRequest(
                                    1L, 9L, 11L, 702L,
                                    new ProtocolAccountRef(
                                            383L, ProtocolBackend.ANDROID,
                                            "acc-android", "922"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            assertThat(result.commandIds())
                    .containsExactly("cmd-query-web", "cmd-query-android");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("group.members.query.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateType)
                    .containsOnly("PULL_TASK_MEMBER_QUERY");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateId)
                    .containsExactly(701L, 702L);
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                            ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaKey)
                    .containsExactly("acc-web", "acc-android");

            JsonNode reference = objectMapper.readTree(rows.get(0).getPayloadJson());
            assertThat(reference.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder(
                            "tenantId", "pullTaskId", "groupExecutionId", "queryId", "source");
            assertThat(reference.path("queryId").asLong()).isEqualTo(701L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueueNormalGroupCreationCommands_routesEachBackendToItsOwnCommandTopic() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-normal-web", "cmd-normal-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueueNormalGroupCreationCommands(List.of(
                            new ProtocolNormalGroupCreationCommandRequest(
                                    1L, 9L, 21L, 31L, "CREATOR_SAVE_MEMBER",
                                    "CONTACT_PREPARE",
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB, "acc-web", "911")),
                            new ProtocolNormalGroupCreationCommandRequest(
                                    1L, 9L, 21L, 31L, "MEMBER_SAVE_CREATOR",
                                    "CONTACT_PREPARE",
                                    new ProtocolAccountRef(
                                            383L, ProtocolBackend.ANDROID, "acc-android", "922"))));

            assertThat(result.batchId()).isEqualTo("normal-group-creation:9");
            assertThat(result.commandIds())
                    .containsExactly("cmd-normal-web", "cmd-normal-android");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            NormalGroupCreationKafkaProperties.DEFAULT_WEB_COMMAND_TOPIC,
                            NormalGroupCreationKafkaProperties.DEFAULT_ANDROID_COMMAND_TOPIC);
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaKey)
                    .containsExactly("acc-web", "acc-android");
            assertThat(rows).extracting(ProtocolCommandOutbox::getProtocolBackend)
                    .containsExactly("WEB", "ANDROID");
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("group.normal_creation.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateType)
                    .containsOnly("NORMAL_GROUP_CREATION_ITEM");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateId)
                    .containsOnly(21L);

            Map<String, Object> payload = objectMapper.readValue(
                    rows.get(1).getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "taskId", 9,
                    "itemId", 21,
                    "memberId", 31,
                    "direction", "MEMBER_SAVE_CREATOR",
                    "action", "CONTACT_PREPARE",
                    "source", "normal_group_creation"));
            assertThat(rows.get(1).getPayloadJson())
                    .doesNotContain("wsPhone")
                    .doesNotContain("accountId")
                    .doesNotContain("protocolBackend");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskPullerInviteCommandsPersistsOnlyReferencesAndRoutesByBackend() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-invite-web", "cmd-invite-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskPullerInviteCommands(List.of(
                            new ProtocolPullTaskPullerInviteCommandRequest(
                                    1L, 9L, 11L, 701L,
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB, "acc-web", "911")),
                            new ProtocolPullTaskPullerInviteCommandRequest(
                                    1L, 9L, 11L, 702L,
                                    new ProtocolAccountRef(
                                            383L, ProtocolBackend.ANDROID, "acc-android", "922"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("group.participants.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                            ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            Map<String, Object> payload = objectMapper.readValue(
                    rows.get(0).getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "actionId", 701,
                    "source", "pull_task_puller_invite"));
            assertThat(rows.get(0).getPayloadJson())
                    .doesNotContain("groupJid")
                    .doesNotContain("participants")
                    .doesNotContain("accountId");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskManagerAdminCommandsPersistsOnlyReferencesAndRoutesByPromoterBackend() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-promote-web", "cmd-promote-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskManagerAdminCommands(List.of(
                            new ProtocolPullTaskManagerAdminCommandRequest(
                                    1L, 9L, 11L, 711L,
                                    new ProtocolAccountRef(
                                            392L, ProtocolBackend.WEB, "promoter-web", "933")),
                            new ProtocolPullTaskManagerAdminCommandRequest(
                                    1L, 9L, 12L, 712L,
                                    new ProtocolAccountRef(
                                            393L, ProtocolBackend.ANDROID, "promoter-android", "944"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("group.participants.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateType)
                    .containsOnly("PULL_TASK_ACCOUNT_ACTION");
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                            ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaKey)
                    .containsExactly("promoter-web", "promoter-android");
            Map<String, Object> payload = objectMapper.readValue(
                    rows.get(0).getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "actionId", 711,
                    "source", "pull_task_manager_admin"));
            assertThat(rows.get(0).getPayloadJson())
                    .doesNotContain("groupJid")
                    .doesNotContain("participants")
                    .doesNotContain("wsPhone")
                    .doesNotContain("accountId");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskGroupSettingsCommandsPersistsOnlyReferencesAndRoutesByManagerBackend()
            throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-settings-web", "cmd-settings-android"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(2);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskGroupSettingsCommands(List.of(
                            new ProtocolPullTaskGroupSettingsCommandRequest(
                                    1L, 9L, 11L, 811L,
                                    new ProtocolAccountRef(
                                            392L, ProtocolBackend.WEB, "manager-web", "933")),
                            new ProtocolPullTaskGroupSettingsCommandRequest(
                                    1L, 9L, 12L, 812L,
                                    new ProtocolAccountRef(
                                            393L, ProtocolBackend.ANDROID, "manager-android", "944"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            List<ProtocolCommandOutbox> rows = capturedRows();
            assertThat(rows).extracting(ProtocolCommandOutbox::getCommandType)
                    .containsOnly("group.settings.requested");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateType)
                    .containsOnly("PULL_TASK_ACCOUNT_ACTION");
            assertThat(rows).extracting(ProtocolCommandOutbox::getAggregateId)
                    .containsExactly(811L, 812L);
            // 与 promote、邀请、批量 add 同路由：Web 进 master，Android 进 group-action。
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaTopic)
                    .containsExactly(
                            ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                            ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
            // key 为管理员协议账号，保证 promote 与群设置命令同分区且严格保序。
            assertThat(rows).extracting(ProtocolCommandOutbox::getKafkaKey)
                    .containsExactly("manager-web", "manager-android");
            Map<String, Object> payload = objectMapper.readValue(
                    rows.get(0).getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "actionId", 811,
                    "source", "pull_task_group_settings"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskGroupSettingsCommandsKeepsGroupAndAccountFactsOutOfOutbox()
            throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-settings-web"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(1);
        TenantContext.set(1L);
        try {
            service.enqueuePullTaskGroupSettingsCommands(List.of(
                    new ProtocolPullTaskGroupSettingsCommandRequest(
                            1L, 9L, 11L, 811L,
                            new ProtocolAccountRef(
                                    392L, ProtocolBackend.WEB, "manager-web", "933"))));

            // Outbox 只存轻量引用，群 JID、号码和设置项都由发布时的 hydrator 补全。
            // 设置项按 JSON 键断言：source 值里含 "settings" 子串，裸子串匹配会误判。
            assertThat(capturedRows().get(0).getPayloadJson())
                    .doesNotContain("groupJid")
                    .doesNotContain("wsPhone")
                    .doesNotContain("accountPhone")
                    .doesNotContain("\"setting\":")
                    .doesNotContain("\"enabled\":");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskBatchAddPersistsCallReferenceAndRoutesByPullerBackend() throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-batch-add"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(1);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskBatchAddCommands(List.of(
                            new ProtocolPullTaskBatchAddCommandRequest(
                                    1L, 9L, 11L, 801L,
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB, "puller-web", "911"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            ProtocolCommandOutbox row = capturedRows().get(0);
            assertThat(row.getCommandType()).isEqualTo("group.participants.requested");
            assertThat(row.getAggregateType()).isEqualTo("PULL_TASK_PULL_CALL");
            assertThat(row.getAggregateId()).isEqualTo(801L);
            assertThat(row.getKafkaTopic())
                    .isEqualTo(ProtocolMasterCommandProperties.DEFAULT_TOPIC);
            Map<String, Object> payload = objectMapper.readValue(
                    row.getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "pullCallId", 801,
                    "source", "pull_task_batch_add"));
            assertThat(row.getPayloadJson())
                    .doesNotContain("groupJid")
                    .doesNotContain("participants")
                    .doesNotContain("accountId");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enqueuePullTaskMaterialAdminPersistsMaterialReferenceAndRoutesByManagerBackend()
            throws Exception {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-material-admin"), List.of());
        when(mapper.batchInsertPending(anyList())).thenReturn(1);
        TenantContext.set(1L);
        try {
            ProtocolCommandOutboxEnqueueResult result =
                    service.enqueuePullTaskMaterialAdminCommands(List.of(
                            new ProtocolPullTaskMaterialAdminCommandRequest(
                                    1L, 9L, 11L, 601L, 501L,
                                    new ProtocolAccountRef(
                                            382L, ProtocolBackend.WEB,
                                            "manager-web", "911"))));

            assertThat(result.batchId()).isEqualTo("pull-task:9");
            ProtocolCommandOutbox row = capturedRows().get(0);
            assertThat(row.getCommandType()).isEqualTo("group.participants.requested");
            assertThat(row.getAggregateType()).isEqualTo("PULL_TASK_MATERIAL_MEMBER");
            assertThat(row.getAggregateId()).isEqualTo(601L);
            assertThat(row.getKafkaTopic())
                    .isEqualTo(ProtocolMasterCommandProperties.DEFAULT_TOPIC);
            Map<String, Object> payload = objectMapper.readValue(
                    row.getPayloadJson(), new TypeReference<>() {
                    });
            assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", 1,
                    "pullTaskId", 9,
                    "groupExecutionId", 11,
                    "materialId", 601,
                    "managerGroupAccountId", 501,
                    "source", "pull_task_material_admin"));
            assertThat(row.getPayloadJson())
                    .doesNotContain("groupJid")
                    .doesNotContain("participants")
                    .doesNotContain("protocolAccountId");
        } finally {
            TenantContext.clear();
        }
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
    void enqueueOnlineCommands_reusesCurrentTraceForEveryRowInTheRequestScope() {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-trace-1", "cmd-trace-2"), List.of("batch-trace"));
        when(mapper.batchInsertPending(anyList())).thenReturn(2);

        try (TraceContext.Scope ignored = TraceContext.open(FIXED_TRACE_ID)) {
            service.enqueueOnlineCommands(List.of(
                    onlineCommand(101L, "acc-101", CredentialFormat.BAILEYS_JSON, 11L),
                    onlineCommand(102L, "acc-102", CredentialFormat.BAILEYS_JSON, 12L)));
        }

        assertThat(capturedRows()).extracting(ProtocolCommandOutbox::getTraceId)
                .containsOnly(FIXED_TRACE_ID);
    }

    @Test
    void enqueueOnlineCommands_withoutContextSharesTraceOnlyWithinTheSameAggregate() {
        TestableProtocolCommandOutboxService service = newService(
                List.of("cmd-trace-1", "cmd-trace-2", "cmd-trace-3"),
                List.of("batch-trace"));
        when(mapper.batchInsertPending(anyList())).thenReturn(3);

        service.enqueueOnlineCommands(List.of(
                onlineCommand(101L, "acc-101", CredentialFormat.BAILEYS_JSON, 11L),
                onlineCommand(101L, "acc-101", CredentialFormat.BAILEYS_JSON, 11L),
                onlineCommand(102L, "acc-102", CredentialFormat.BAILEYS_JSON, 12L)));

        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).extracting(ProtocolCommandOutbox::getTraceId)
                .allMatch(TraceIds::isValid);
        assertThat(rows.get(0).getTraceId()).isEqualTo(rows.get(1).getTraceId());
        assertThat(rows.get(2).getTraceId()).isNotEqualTo(rows.get(0).getTraceId());
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
                        ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                        "protocol.android.lifecycle.commands.test",
                        ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                        ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
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
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.lifecycle.commands.test");
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
                        ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                        "protocol.android.lifecycle.commands.test",
                        ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                        ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
        ProtocolOfflineCommandRequest command = new ProtocolOfflineCommandRequest(
                100L,
                "acc_100",
                "batch_offline",
                ProtocolBackend.ANDROID);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueOfflineCommands(List.of(command));

        ProtocolCommandOutbox row = capturedRows().get(0);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.lifecycle.commands.test");
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
                        null,
                        null),
                "cmd_android",
                750,
                2_500L);
        ProtocolMessageOutboxCommand outboxCommand = new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.ANDROID,
                "protocol.android.message.commands.v1",
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
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.android.message.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_android");
        assertThat(row.getProtocolBackend()).isEqualTo("ANDROID");
        assertThat(row.getNextRetryAt()).isEqualTo(2_500L);
        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("wsPhone", "919000000001")
                .containsEntry("messageType", "TEXT")
                .doesNotContainKeys("notBeforeAt");
        verify(dispatchTrigger).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueMessageCommands_acceptsHistoricalCorrelationAndUsesMemberAggregate() {
        TestableProtocolCommandOutboxService service = newService(List.of(), List.of());
        MessageSendCommand command = new MessageSendCommand(
                new ProtocolAccountRef(501L, ProtocolBackend.WEB, "acc_web", "919000000001"),
                new MessageSendCommand.MessageTarget("120363history@g.us"),
                new MessageSendCommand.MessagePayload(
                        MessageType.TEXT,
                        new MessageSendCommand.MessageContent("offer", null, null, null),
                        false),
                new MessageSendCommand.MessageCorrelation(
                        1L,
                        "historical_group_pull",
                        null,
                        null,
                        new MessageSendCommand.HistoricalGroupCorrelation(91L, 301L)),
                "cmd_historical",
                MessageSendCommand.DEFAULT_SEND_INTERVAL_MS,
                0L);
        ProtocolMessageOutboxCommand outboxCommand = new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.WEB,
                "protocol.master.commands.v1",
                "acc_web",
                Map.of(
                        "tenantId", 1L,
                        "historicalExecutionId", 91L,
                        "historicalMemberId", 301L,
                        "source", "historical_group_pull"));
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        service.enqueueMessageCommands(List.of(outboxCommand));

        ProtocolCommandOutbox row = capturedRows().get(0);
        assertThat(row.getAggregateType()).isEqualTo("HISTORICAL_GROUP_PULL_MEMBER");
        assertThat(row.getAggregateId()).isEqualTo(301L);
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

    @Test
    void cancelPendingAccountOnlineCommands_cancelsOnlyPendingAccountOnlineRows() {
        TestableProtocolCommandOutboxService service = newService(List.of(), List.of());
        when(mapper.cancelPendingAccountOnlineCommandsInternal(
                eq(List.of(100L, 101L)),
                eq("ACCOUNT"),
                eq("account.online.requested"),
                eq(ProtocolCommandOutboxStatus.PENDING.code()),
                eq(ProtocolCommandOutboxStatus.CANCELED.code()),
                anyLong())).thenReturn(2);

        int canceled = service.cancelPendingAccountOnlineCommands(List.of(100L, 101L));

        assertThat(canceled).isEqualTo(2);
        verify(mapper).cancelPendingAccountOnlineCommandsInternal(
                eq(List.of(100L, 101L)),
                eq("ACCOUNT"),
                eq("account.online.requested"),
                eq(ProtocolCommandOutboxStatus.PENDING.code()),
                eq(ProtocolCommandOutboxStatus.CANCELED.code()),
                anyLong());
    }

    @Test
    void cancelPendingPullTaskCommands_passesScopeAndBusinessTypesToMapper() {
        TestableProtocolCommandOutboxService service = newService(List.of(), List.of());
        when(mapper.cancelPendingPullTaskCommandsInternal(
                eq(9L), eq(11L),
                eq("PULL_TASK_ACCOUNT_ACTION"),
                eq("PULL_TASK_PULL_CALL"),
                eq("PULL_TASK_MATERIAL_MEMBER"),
                eq("PULL_TASK_MEMBER_QUERY"),
                eq(List.of(
                        ProtocolCommandOutboxStatus.PENDING.code(),
                        ProtocolCommandOutboxStatus.LOCKED.code())),
                eq(ProtocolCommandOutboxStatus.DISPATCHING.code()),
                eq(ProtocolCommandOutboxStatus.CANCELED.code()),
                eq(ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code()),
                eq("PULL_TASK_ENDED"), eq(900L))).thenReturn(3);

        int canceled = service.cancelPendingPullTaskCommands(9L, 11L, 900L);

        assertThat(canceled).isEqualTo(3);
        verify(mapper).cancelPendingPullTaskCommandsInternal(
                9L, 11L,
                "PULL_TASK_ACCOUNT_ACTION",
                "PULL_TASK_PULL_CALL",
                "PULL_TASK_MATERIAL_MEMBER",
                "PULL_TASK_MEMBER_QUERY",
                List.of(
                        ProtocolCommandOutboxStatus.PENDING.code(),
                        ProtocolCommandOutboxStatus.LOCKED.code()),
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(),
                "PULL_TASK_ENDED", 900L);
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds, List<String> batchIds) {
        return newService(commandIds, batchIds, ProtocolAccountCommandProperties.DEFAULT_TOPIC,
                ProtocolMasterCommandProperties.DEFAULT_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds,
                                                            List<String> batchIds,
                                                            String accountCommandTopic,
                                                            String masterCommandTopic) {
        return newService(commandIds, batchIds, accountCommandTopic, masterCommandTopic,
                ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC,
                ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds,
                                                            List<String> batchIds,
                                                            String accountCommandTopic,
                                                            String masterCommandTopic,
                                                            String androidLifecycleCommandTopic,
                                                            String androidMessageCommandTopic,
                                                            String androidGroupJoinCommandTopic) {
        ProtocolAccountCommandProperties accountProperties = new ProtocolAccountCommandProperties();
        accountProperties.setTopic(accountCommandTopic);
        ProtocolMasterCommandProperties masterProperties = new ProtocolMasterCommandProperties();
        masterProperties.setTopic(masterCommandTopic);
        ProtocolAndroidCommandProperties androidProperties = new ProtocolAndroidCommandProperties();
        androidProperties.setLifecycleTopic(androidLifecycleCommandTopic);
        androidProperties.setMessageTopic(androidMessageCommandTopic);
        androidProperties.setGroupJoinTopic(androidGroupJoinCommandTopic);
        NormalGroupCreationKafkaProperties normalGroupProperties =
                new NormalGroupCreationKafkaProperties();
        return new TestableProtocolCommandOutboxService(mapper, objectMapper, dispatchTrigger, accountProperties,
                masterProperties, androidProperties, normalGroupProperties, commandIds, batchIds);
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
                                                     NormalGroupCreationKafkaProperties normalGroupProperties,
                                                     List<String> commandIds,
                                                     List<String> batchIds) {
            super(mapper, objectMapper, dispatchTrigger, accountProperties, masterProperties,
                    androidProperties, normalGroupProperties);
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
