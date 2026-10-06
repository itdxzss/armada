package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfileCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 群资料命令的跨组件契约回归，直接串联真实 Dispatcher、Outbox Service 和 Payload Hydrator。
 *
 * <p>仅在数据读写、账号查询和发送触发器边界使用 Mockito；本类不验证 SQL、事务提交、
 * Kafka 或 WhatsApp 生效，不能替代 H2 Mapper 测试与真实协议验收。</p>
 */
class PullTaskGroupProfileCommandContractTest {

    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 100L;
    private static final long EXECUTION_ID = 11L;
    private static final long ACTION_ID = 811L;
    private static final long ROLE_ID = 501L;
    private static final long ACCOUNT_ID = 901L;
    private static final long NOW = 1_800_000_000_000L;
    private static final String GROUP_NAME = "资料链路核对群 🌏";
    private static final String DESCRIPTION = "完整简介第一行\n第二行 🌏\n群规则与说明";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskStandardGroupSettingMapper settingMapper = mock(PullTaskStandardGroupSettingMapper.class);
    private final AccountProtocolLookupService accountLookup = mock(AccountProtocolLookupService.class);
    private final ProtocolCommandOutboxMapper outboxMapper = mock(ProtocolCommandOutboxMapper.class);
    private final ProtocolCommandDispatchTrigger dispatchTrigger = mock(ProtocolCommandDispatchTrigger.class);
    private final List<PullTaskAccountAction> actions = new ArrayList<>();
    private final List<ProtocolCommandOutbox> queued = new ArrayList<>();
    private final PullTaskGroupExecution execution = execution();
    private final PullTaskGroupAccount actor = actor();
    private final ProtocolCommandOutboxServiceImpl outbox = new ProtocolCommandOutboxServiceImpl(
            outboxMapper, objectMapper, dispatchTrigger, new ProtocolAccountCommandProperties(),
            new ProtocolMasterCommandProperties(), new ProtocolAndroidCommandProperties(),
            new NormalGroupCreationKafkaProperties());
    private final PullTaskGroupProfileDispatcher dispatcher = new PullTaskGroupProfileDispatcher(
            settingMapper, actionMapper, accountMapper, accountLookup, outbox);
    private final PullTaskGroupProfilePayloadHydrator profileHydrator = new PullTaskGroupProfilePayloadHydrator(
            actionMapper, accountMapper, executionMapper, settingMapper,
            mock(PullTaskGroupAvatarService.class), objectMapper);
    private final PullTaskGroupSettingsPayloadHydrator settingsHydrator = new PullTaskGroupSettingsPayloadHydrator(
            actionMapper, accountMapper, executionMapper, objectMapper);

    @BeforeEach
    void prepareDataBoundaries() {
        TenantContext.set(TENANT_ID);
        when(settingMapper.selectByTaskId(TASK_ID)).thenReturn(setting());
        when(accountMapper.selectByExecutionAndRole(eq(EXECUTION_ID), any(Integer.class)))
                .thenReturn(List.of(actor));
        when(accountMapper.selectById(ROLE_ID)).thenReturn(actor);
        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(execution);
        when(actionMapper.selectByExecutionAndType(EXECUTION_ID, PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code()))
                .thenAnswer(invocation -> List.copyOf(actions));
        when(actionMapper.insertIfAbsent(any(PullTaskAccountAction.class))).thenAnswer(invocation -> {
            PullTaskAccountAction action = invocation.getArgument(0);
            action.setId(ACTION_ID);
            action.setActionStatus(PullTaskActionStatus.PENDING.code());
            actions.add(action);
            return 1;
        });
        when(actionMapper.submitAttempt(eq(ACTION_ID), anyList(), anyString(), anyLong())).thenAnswer(invocation -> {
            PullTaskAccountAction action = actions.get(0);
            action.setCommandId(invocation.getArgument(2));
            action.setAttemptNo(1);
            action.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
            return 1;
        });
        when(actionMapper.selectByCommandId(anyString())).thenAnswer(invocation -> actions.stream()
                .filter(action -> action.getCommandId().equals(invocation.getArgument(0)))
                .findFirst().orElse(null));
        when(outboxMapper.batchInsertPending(anyList())).thenAnswer(invocation -> {
            List<ProtocolCommandOutbox> rows = invocation.getArgument(0);
            queued.addAll(rows);
            return rows.size();
        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"WEB,GROUP_CREATE", "ANDROID,GROUP_CREATE", "WEB,MANAGER_PULLER_CONTACT", "ANDROID,MANAGER_PULLER_CONTACT"})
    void realDispatchProducesHydratableProfileCommandForActualBackend(
            ProtocolBackend backend, PullTaskExecutionStage stage) throws Exception {
        execution.setStage(stage.code());
        when(accountLookup.findActiveProtocolRefs(List.of(ACCOUNT_ID))).thenReturn(List.of(account(backend)));
        when(accountLookup.findOnlineProtocolRefs(List.of(ACCOUNT_ID))).thenReturn(List.of(account(backend)));

        dispatcher.dispatchIfDue(execution, PullTaskGroupSettingTiming.BEFORE_PULL, NOW);

        assertThat(queued).hasSize(1);
        ProtocolCommandOutbox row = queued.get(0);
        assertThat(row.getCommandType()).isEqualTo(ProtocolPullTaskGroupProfileCommandRequest.COMMAND_TYPE);
        assertThat(row.getAggregateType()).isEqualTo(ProtocolPullTaskGroupProfileCommandRequest.AGGREGATE_TYPE);
        assertThat(row.getAggregateId()).isEqualTo(ACTION_ID);
        assertThat(row.getProtocolBackend()).isEqualTo(backend.name());
        assertThat(row.getKafkaTopic()).isEqualTo(backend == ProtocolBackend.WEB
                ? ProtocolMasterCommandProperties.DEFAULT_TOPIC : ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);
        assertThat(row.getKafkaKey()).isEqualTo(account(backend).protocolAccountId());
        JsonNode reference = objectMapper.readTree(row.getPayloadJson());
        assertThat(reference.path("source").asText()).isEqualTo(ProtocolPullTaskGroupProfileCommandRequest.SOURCE);
        assertThat(reference.has("description")).isFalse();
        assertThat(profileHydrator.supports(row)).isTrue();
        assertThat(settingsHydrator.supports(row)).isFalse();

        JsonNode payload = profileHydrator.hydrate(row, reference);

        assertThat(payload.path("subject").asText()).isEqualTo(GROUP_NAME);
        assertThat(payload.path("description").asText()).isEqualTo(DESCRIPTION);
        assertThat(payload.has("groupDescription")).isFalse();
        assertThat(payload.path("groupJid").asText()).isEqualTo(execution.getGroupJid());
        assertThat(payload.path("source").asText()).isEqualTo(ProtocolPullTaskGroupProfileCommandRequest.SOURCE);
        assertThat(payload.path("protocolBackend").asText()).isEqualTo(backend.name());
        assertThat(payload.path("protocolAccountId").asText()).isEqualTo(account(backend).protocolAccountId());
        assertThat(payload.path("wsPhone").asText()).isEqualTo(actor.getAccountPhone());
        assertThat(payload.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(actions.get(0).getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        verify(accountMapper).selectByExecutionAndRole(EXECUTION_ID, stage == PullTaskExecutionStage.GROUP_CREATE
                ? PullTaskGroupAccountRole.PROMOTER.code() : PullTaskGroupAccountRole.MANAGER.code());
        verify(dispatchTrigger).dispatchAfterCommit(List.of(row));
        assertThat(TenantContext.get()).isEqualTo(TENANT_ID);

        dispatcher.dispatchIfDue(execution, PullTaskGroupSettingTiming.BEFORE_PULL, NOW + 1);
        assertThat(queued).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ProtocolBackend.class)
    void newGroupProfileUsesFrozenNumberedSubjectForBothBackends(ProtocolBackend backend) throws Exception {
        execution.setCreateStep(PullTaskGroupCreateStep.APPLY_PROFILE.code());
        execution.setGroupSubject(GROUP_NAME + "-10");
        when(accountLookup.findActiveProtocolRefs(List.of(ACCOUNT_ID))).thenReturn(List.of(account(backend)));
        when(accountLookup.findOnlineProtocolRefs(List.of(ACCOUNT_ID))).thenReturn(List.of(account(backend)));
        dispatcher.dispatchIfDue(execution, PullTaskGroupSettingTiming.BEFORE_PULL, NOW);

        ProtocolCommandOutbox row = queued.get(0);
        JsonNode payload = profileHydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.path("subject").asText()).isEqualTo(GROUP_NAME + "-10");
        assertThat(payload.path("description").asText()).isEqualTo(DESCRIPTION);
    }

    @ParameterizedTest
    @EnumSource(ProtocolBackend.class)
    void legacySingleSettingCommandCannotHydrateAGroupProfileAction(ProtocolBackend backend) throws Exception {
        when(accountLookup.findOnlineProtocolRefs(List.of(ACCOUNT_ID))).thenReturn(List.of(account(backend)));
        dispatcher.dispatchIfDue(execution, PullTaskGroupSettingTiming.BEFORE_PULL, NOW);
        queued.clear();

        outbox.enqueuePullTaskGroupSettingsCommands(List.of(new ProtocolPullTaskGroupSettingsCommandRequest(
                TENANT_ID, TASK_ID, EXECUTION_ID, ACTION_ID, account(backend))));
        ProtocolCommandOutbox legacy = queued.get(0);
        actions.get(0).setCommandId(legacy.getCommandId());

        assertThat(legacy.getCommandType()).isEqualTo("group.settings.requested");
        assertThat(profileHydrator.supports(legacy)).isFalse();
        assertThat(settingsHydrator.supports(legacy)).isTrue();
        JsonNode reference = objectMapper.readTree(legacy.getPayloadJson());
        assertThat(reference.path("source").asText()).isEqualTo(ProtocolPullTaskGroupSettingsCommandRequest.SOURCE);
        assertThatThrownBy(() -> settingsHydrator.hydrate(legacy, reference))
                .isInstanceOf(BusinessException.class).hasMessageContaining("群设置命令动作类型非法");
    }

    private static ProtocolAccountRef account(ProtocolBackend backend) {
        return new ProtocolAccountRef(ACCOUNT_ID, backend, "offline-profile-" + backend.name(), "10000000000");
    }

    private static PullTaskStandardGroupSetting setting() {
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setTenantId(TENANT_ID);
        row.setTaskId(TASK_ID);
        row.setGroupSettingEnabled(1);
        row.setSettingTiming(PullTaskGroupSettingTiming.BEFORE_PULL.code());
        row.setGroupName(GROUP_NAME);
        row.setGroupDescription(DESCRIPTION);
        row.setMaterialFilenameAsGroupName(0);
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(EXECUTION_ID);
        row.setTenantId(TENANT_ID);
        row.setTaskId(TASK_ID);
        row.setGroupJid("120363000000000000@g.us");
        row.setGroupSubject(GROUP_NAME);
        row.setStage(PullTaskExecutionStage.GROUP_CREATE.code());
        return row;
    }

    private static PullTaskGroupAccount actor() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(ROLE_ID);
        row.setTenantId(TENANT_ID);
        row.setTaskId(TASK_ID);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setAccountId(ACCOUNT_ID);
        row.setAccountPhone("10000000000");
        return row;
    }
}
