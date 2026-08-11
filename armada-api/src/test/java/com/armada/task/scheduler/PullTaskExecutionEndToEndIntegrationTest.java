package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskPullerInviteCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskManagerAdminCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.service.PullTaskContactSaveResultService;
import com.armada.task.service.PullTaskManagerJoinResultService;
import com.armada.task.service.PullTaskManagerAdminResultService;
import com.armada.task.service.PullTaskPullerInviteResultService;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.armada.task.service.impl.PullTaskContactSaveResultServiceImpl;
import com.armada.task.service.impl.PullTaskManagerJoinResultServiceImpl;
import com.armada.task.service.impl.PullTaskManagerAdminResultServiceImpl;
import com.armada.task.service.impl.PullTaskPullerInviteResultServiceImpl;
import com.armada.task.service.impl.PullTaskProtocolResultCallbackServiceImpl;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import com.armada.task.service.impl.PullTaskPullCallResultCoordination;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 用真实 Mapper XML 纵向验证链接校验到父任务收口的最小执行闭环。 */
@SpringJUnitConfig(PullTaskExecutionEndToEndIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskExecutionEndToEndIntegrationTest {

    private static final String GROUP_JID = "120363group@g.us";
    private static final ProtocolAccountRef MANAGER = account(901L);
    private static final ProtocolAccountRef PROMOTER = account(906L);
    private static final ProtocolAccountRef PULLER = account(902L);
    private static final ProtocolAccountRef STATION = account(903L);
    private static final AtomicBoolean MANAGER_PROMOTED = new AtomicBoolean();

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskExecutionDispatchCoordinator coordinator;
    @Autowired private PullTaskManagerJoinResultService managerJoinResultService;
    @Autowired private PullTaskManagerAdminResultService managerAdminResultService;
    @Autowired private PullTaskContactSaveResultService contactSaveResultService;
    @Autowired private PullTaskPullerInviteResultService pullerInviteResultService;
    @Autowired private PullTaskProtocolResultCallbackService protocolResultCallbackService;
    @Autowired private PullTaskMemberQueryAwaitService memberQueryAwaitService;
    @Autowired private com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        MANAGER_PROMOTED.set(false);
        reset(outboxService);
        when(outboxService.enqueuePullTaskGroupJoinCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-pull-e2e"), 1));
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskContactSaveCommandRequest> requests = invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-contact-" + request.actionId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
        when(outboxService.enqueuePullTaskPullerInviteCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskPullerInviteCommandRequest> requests =
                            invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-invite-" + request.actionId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
        when(outboxService.enqueuePullTaskManagerAdminCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskManagerAdminCommandRequest> requests =
                            invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-manager-admin-" + request.actionId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
        when(outboxService.enqueuePullTaskBatchAddCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskBatchAddCommandRequest> requests =
                            invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-batch-" + request.pullCallId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
        when(outboxService.enqueuePullTaskMaterialAdminCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskMaterialAdminCommandRequest> requests =
                            invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-admin-" + request.materialId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        seedTask();
        seedExecutionAndMaterial();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void executesOneLinkAndOneMaterialThroughClosing() throws SQLException {
        for (int round = 0; round < 50 && !"COMPLETED".equals(taskStatus()); round++) {
            long now = 1_000L + round * 1_000L;
            coordinator.dispatchOnce(now);
            applyManagerJoinCallbackIfSubmitted(now + 100L);
            applyManagerAdminCallbackIfSubmitted(now + 150L);
            applyContactCallbacksIfSubmitted(now + 200L);
            applyPullerInviteCallbacksIfSubmitted(now + 300L);
            applyBatchCallbacksIfSubmitted(now + 400L);
            applyMaterialAdminCallbackIfSubmitted(now + 500L);
        }

        TenantContext.set(7L);
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        PullTaskMaterialMember material = materialMapper.selectByExecution(execution.getId()).get(0);
        assertThat(execution.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(execution.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(material.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(material.getAdminStatus()).isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
        assertThat(callMapper.selectByExecution(execution.getId()))
                .singleElement()
                .extracting(call -> call.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(taskStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(memberQueryAwaitService);
    }

    private void applyManagerJoinCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.JOIN_BY_LINK.code());
        if (actions.size() != 1
                || actions.get(0).getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
            return;
        }
        PullTaskAccountAction action = actions.get(0);
        managerJoinResultService.apply(new PullTaskManagerJoinCallback(
                7L, 100L, execution.getId(), action.getId(), action.getCommandId(),
                PullTaskManagerJoinProtocolOutcome.JOINED, GROUP_JID,
                null, null, false, occurredAt));
    }

    private void applyContactCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.SAVE_CONTACT.code());
        for (PullTaskAccountAction action : actions) {
            if (action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
                continue;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            contactSaveResultService.apply(new PullTaskContactSaveCallback(
                    7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                    "account-" + actor.getAccountId(), action.getCommandId(), 1,
                    PullTaskContactSaveOutcome.SUCCESS, null, null, false, occurredAt));
        }
    }

    private void applyManagerAdminCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
        if (actions.size() != 1
                || actions.get(0).getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
            return;
        }
        PullTaskAccountAction action = actions.get(0);
        PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
        PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
        boolean applied = managerAdminResultService.apply(new PullTaskManagerAdminCallback(
                7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                "account-" + actor.getAccountId(), action.getCommandId(), action.getAttemptNo(),
                WhatsappJids.userJid(target.getAccountPhone()),
                PullTaskManagerAdminProtocolOutcome.SUCCESS,
                null, null, false, occurredAt));
        if (applied) {
            MANAGER_PROMOTED.set(true);
        }
    }

    private void applyPullerInviteCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskAccountAction> actions = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.INVITE_TO_GROUP.code());
        for (PullTaskAccountAction action : actions) {
            if (action.getActionStatus() != PullTaskActionStatus.SUBMITTED.code()) {
                continue;
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
            pullerInviteResultService.apply(new PullTaskPullerInviteCallback(
                    7L, 100L, execution.getId(), action.getId(), actor.getAccountId(),
                    "account-" + actor.getAccountId(), action.getCommandId(), 1,
                    WhatsappJids.userJid(target.getAccountPhone()),
                    PullTaskPullerInviteProtocolOutcome.SUCCESS,
                    null, null, false, occurredAt));
        }
    }

    private void applyBatchCallbacksIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        for (var call : callMapper.selectByExecution(execution.getId())) {
            if (call.getCallStatus() != PullTaskPullCallStatus.SUBMITTED.code()) {
                continue;
            }
            long resultAt = occurredAt;
            for (PullTaskGroupAccount station : accountMapper.selectByExecutionAndRole(
                    execution.getId(), com.armada.task.model.enums.PullTaskGroupAccountRole.STATION.code())) {
                if (call.getId().equals(station.getPullCallId())) {
                    applyBatchParticipant(call, station.getAccountPhone(), resultAt++);
                }
            }
            for (PullTaskMaterialMember material : materialMapper.selectByExecution(execution.getId())) {
                if (call.getId().equals(material.getPullCallId())) {
                    applyBatchParticipant(call, material.getNormalizedPhone(), resultAt++);
                }
            }
        }
    }

    private void applyBatchParticipant(
            com.armada.task.model.entity.PullTaskPullCall call,
            String phone,
            long occurredAt) {
        protocolResultCallbackService.handlePullCallParticipant(
                new PullTaskBatchParticipantCallback(
                        7L, 100L, call.getGroupExecutionId(), call.getId(),
                        call.getPullerAccountId(), "account-" + call.getPullerAccountId(),
                        call.getCommandId(), 1, WhatsappJids.userJid(phone),
                        PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                        null, null, false, occurredAt));
    }

    private void applyMaterialAdminCallbackIfSubmitted(long occurredAt) {
        PullTaskGroupExecution execution = executionMapper.selectByTaskId(100L).get(0);
        List<PullTaskMaterialMember> submitted = materialMapper
                .selectByExecution(execution.getId()).stream()
                .filter(material -> material.getAdminStatus()
                        == PullTaskMaterialAdminStatus.SUBMITTED.code())
                .toList();
        if (submitted.isEmpty()) {
            return;
        }
        PullTaskGroupAccount manager = accountMapper.selectByExecutionAndRole(
                execution.getId(),
                com.armada.task.model.enums.PullTaskGroupAccountRole.MANAGER.code()).get(0);
        for (PullTaskMaterialMember material : submitted) {
            protocolResultCallbackService.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                    7L, 100L, execution.getId(), material.getId(), manager.getAccountId(),
                    "account-" + manager.getAccountId(), material.getAdminCommandId(), 1,
                    material.getWaJid(), PullTaskMaterialAdminProtocolOutcome.SUCCESS,
                    null, null, false, occurredAt));
        }
    }

    private void seedTask() throws SQLException {
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (7, 100, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
    }

    private void seedExecutionAndMaterial() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setTaskId(100L);
        execution.setSeq(1);
        execution.setGroupLinkId(9_001L);
        execution.setNormalizedLink("chat.whatsapp.com/AAAA");
        execution.setInviteCode("AAAA");
        execution.setSourceLinkLineNo(1);
        execution.setSourceFileIndex(1);
        execution.setSourceFileName("material.txt");
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);
        execution.setCreatedAt(100L);
        execution.setUpdatedAt(100L);
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setGroupExecutionId(execution.getId());
        material.setMemberSeq(1);
        material.setSourceLineNo(1);
        material.setNormalizedPhone("8613900000001");
        material.setAdminRequired(1);
        material.setCreatedAt(100L);
        material.setUpdatedAt(100L);
        materialMapper.batchInsert(List.of(material));
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String taskStatus() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM pull_task WHERE id=100")) {
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB,
                "account-" + id, "8613800000" + id);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_execution_e2e_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            AccountProtocolLookupService lookup = mock(AccountProtocolLookupService.class);
            when(lookup.findRandomOnlineNormalByGroupId(88L)).thenReturn(Optional.of(MANAGER));
            when(lookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(PULLER));
            when(lookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(STATION));
            when(lookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(MANAGER));
            when(lookup.findActiveProtocolRef(902L)).thenReturn(Optional.of(PULLER));
            when(lookup.findActiveProtocolRef(903L)).thenReturn(Optional.of(STATION));
            when(lookup.findActiveProtocolRefs(anyList()))
                    .thenReturn(List.of(MANAGER, PULLER, STATION));
            return lookup;
        }

        @Bean GroupJoinPort joinPort() {
            GroupJoinPort port = mock(GroupJoinPort.class);
            when(port.join(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new GroupJoinResult(GROUP_JID, GroupJoinOutcome.JOINED));
            return port;
        }

        @Bean GroupMemberListPort memberListPort() {
            GroupMemberListPort port = mock(GroupMemberListPort.class);
            when(port.list(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> List.of(
                    participant(MANAGER, MANAGER_PROMOTED.get()),
                    participant(PROMOTER, true),
                    participant(PULLER, false),
                    participant(STATION, false),
                    new GroupParticipantResult(
                            "8613900000001@s.whatsapp.net", "8613900000001",
                            true, false, "admin")));
            return port;
        }

        @Bean PullTaskMemberQueryAwaitService memberQueryAwaitService() {
            PullTaskMemberQueryAwaitService service = mock(PullTaskMemberQueryAwaitService.class);
            when(service.readOrDefer(
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(invocation -> {
                        PullTaskMemberQueryRequest request = invocation.getArgument(1);
                        List<PullTaskMemberFact> members = request.targetJids().stream()
                                .map(target -> new PullTaskMemberFact(
                                        target, target, target.substring(0, target.indexOf('@')),
                                        true, target.startsWith(PROMOTER.wsPhone())
                                        || target.startsWith("8613900000001")
                                        || target.startsWith(MANAGER.wsPhone())
                                        && MANAGER_PROMOTED.get()))
                                .toList();
                        return PullTaskMemberQueryResult.available(701L, members);
                    });
            return service;
        }

        @Bean ContactPort contactPort() {
            return mock(ContactPort.class);
        }

        @Bean GroupParticipantPort participantPort() {
            GroupParticipantPort port = mock(GroupParticipantPort.class);
            when(port.updateParticipants(
                    any(ProtocolAccountRef.class),
                    org.mockito.ArgumentMatchers.anyString(), anyList(),
                    any(GroupParticipantAction.class)))
                    .thenAnswer(invocation -> {
                        List<String> jids = invocation.getArgument(2);
                        return new GroupParticipantBatchResult(false, jids.stream()
                                .map(jid -> new GroupParticipantBatchResult.Item(jid, "OK", "200"))
                                .toList());
                    });
            return port;
        }

        private static GroupParticipantResult participant(
                ProtocolAccountRef account, boolean admin) {
            return new GroupParticipantResult(
                    account.wsPhone() + "@s.whatsapp.net", account.wsPhone(),
                    admin, false, admin ? "admin" : null);
        }

        @Bean PullTaskParentCompletionService parentCompletion(
                PullTaskMapper taskMapper, PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskParentCompletionService(taskMapper, executionMapper);
        }

        @Bean PullTaskExecutionTransactionService executionTransactions(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskParentCompletionService parentCompletion) {
            return new PullTaskExecutionTransactionService(
                    taskMapper, settingMapper, executionMapper);
        }

        @Bean PullTaskLinkValidationProcessor linkProcessor(
                PullTaskExecutionTransactionService transactions) {
            return new PullTaskLinkValidationProcessor(transactions);
        }

        @Bean PullTaskManagerJoinProcessor managerJoinProcessor(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper, PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper, AccountProtocolLookupService lookup,
                PullTaskParentCompletionService parentCompletion, GroupJoinPort joinPort,
                PullTaskMemberQueryAwaitService memberQueryAwaitService,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskExecutionTransactionService executionTransactions) {
            PullTaskManagerJoinResources resources = new PullTaskManagerJoinResources(
                    executionMapper, lookup, parentCompletion, outboxService, properties);
            PullTaskManagerJoinTransactionService transactions =
                    new PullTaskManagerJoinTransactionService(
                            taskMapper, settingMapper, accountMapper, actionMapper, resources);
            PullTaskManagerJoinProtocolExecutor protocolExecutor =
                    new PullTaskManagerJoinProtocolExecutor(
                            joinPort, mock(com.armada.group.service.GroupInviteLinkService.class));
            return new PullTaskManagerJoinProcessor(
                    executionTransactions, transactions,
                    mock(PullTaskSupplementManagerProcessor.class),
                    protocolExecutor, memberQueryAwaitService);
        }

        @Bean com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService() {
            return mock(com.armada.platform.protocol.service.ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskManagerJoinResultService managerJoinResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskParentCompletionService completionService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskManagerJoinResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, completionService, properties,
                    delayPolicy, mock(com.armada.group.service.GroupInviteLinkService.class));
        }

        @Bean PullTaskManagerAdminResultService managerAdminResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskExecutionDispatchProperties properties,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskManagerAdminResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, properties, delayPolicy);
        }

        @Bean PullTaskContactSaveResultService contactSaveResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskContactSaveResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, delayPolicy);
        }

        @Bean PullTaskPullerInviteResultService pullerInviteResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullerInviteDelayPolicy delayPolicy) {
            return new PullTaskPullerInviteResultServiceImpl(
                    actionMapper, accountMapper, executionMapper, delayPolicy);
        }

        @Bean PullTaskManagerPullerContactProcessor managerPullerContactProcessor(
                PullTaskMapper taskMapper, PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper, PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper, AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskManagerPullerContactResources resources =
                    new PullTaskManagerPullerContactResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskManagerPullerContactTransactionService transactions =
                    new PullTaskManagerPullerContactTransactionService(
                            taskMapper, settingMapper, accountMapper, actionMapper, resources);
            FixedAccountGroupMetadataPort metadataPort =
                    mock(FixedAccountGroupMetadataPort.class);
            GroupMetadataResult metadata = mock(GroupMetadataResult.class);
            when(metadata.memberAddMode()).thenReturn(true);
            when(metadataPort.getMetadata(any(), any())).thenReturn(metadata);
            return new PullTaskManagerPullerContactProcessor(
                    transactions,
                    mock(PullTaskSupplementPullerProcessor.class),
                    metadataPort,
                    mock(GroupSettingsPort.class));
        }

        @Bean PullTaskPullerInviteProcessor pullerInviteProcessor(
                PullTaskMapper taskMapper, PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskPullerInviteResources resources =
                    new PullTaskPullerInviteResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskPullerInviteTransactionService transactions =
                    new PullTaskPullerInviteTransactionService(
                            taskMapper, accountMapper, actionMapper, resources);
            return new PullTaskPullerInviteProcessor(transactions);
        }

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stationSelection(
                PullTaskGroupAccountMapper accountMapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStationSelectionService(accountMapper, lookup);
        }

        @Bean PullTaskPullWavePlanningSelection wavePlanningSelection(
                PullTaskStationSelectionService stationSelection,
                PullTaskBatchSizeSelector sizeSelector) {
            return new PullTaskPullWavePlanningSelection(stationSelection, sizeSelector);
        }

        @Bean PullTaskPullWavePlanningResources wavePlanningResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskPullWavePlanningSelection selection) {
            return new PullTaskPullWavePlanningResources(
                    executionMapper, waveMapper, callMapper, attemptMapper, selection);
        }

        @Bean PullTaskPullWavePlanningTransactionService wavePlanningService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskPullWavePlanningResources resources) {
            return new PullTaskPullWavePlanningTransactionService(
                    taskMapper, settingMapper, materialMapper, accountMapper, resources);
        }

        @Bean PullTaskStickyPullerTransactionService stickyPullers(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStickyPullerTransactionService(
                    executionMapper, accountMapper, callMapper, attemptMapper, lookup);
        }

        @Bean PullTaskPullWaveSettlementResources settlementResources(
                PullTaskMapper taskMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskPullWaveSettlementResources(
                    taskMapper, executionMapper, waveMapper, attemptMapper, materialMapper);
        }

        @Bean PullTaskPullWaveSettlementTransactionService settlementService(
                PullTaskPullWaveSettlementResources resources,
                PullTaskPullWavePlanningTransactionService planning) {
            return new PullTaskPullWaveSettlementTransactionService(resources, planning);
        }

        @Bean PullTaskPullerStationContactProcessor pullerStationContacts(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullerStationContactResources resources) {
            return new PullTaskPullerStationContactProcessor(
                    new PullTaskPullerStationContactTransactionService(
                            taskMapper, accountMapper, actionMapper, resources));
        }

        @Bean PullTaskPullerStationContactResources pullerStationContactResources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                PullTaskPullCallMapper callMapper,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullerStationContactResources(
                    executionMapper, lookup, callMapper, outboxService, properties);
        }

        @Bean PullTaskBatchAddPersistence batchPersistence(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper) {
            return new PullTaskBatchAddPersistence(
                    executionMapper, waveMapper, callMapper, attemptMapper);
        }

        @Bean PullTaskBatchAddResources batchResources(
                PullTaskBatchAddPersistence persistence,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskBatchAddResources(
                    persistence, lookup, outboxService, delayPolicy);
        }

        @Bean PullTaskBatchAddProcessor batchProcessor(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskBatchAddResources resources) {
            return new PullTaskBatchAddProcessor(new PullTaskBatchAddTransactionService(
                    taskMapper, settingMapper, accountMapper, materialMapper, resources));
        }

        @Bean PullTaskClosingTransactionService pullClosing(
                PullTaskMapper taskMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskParentCompletionService parentCompletion) {
            return new PullTaskClosingTransactionService(
                    taskMapper, executionMapper, accountMapper, parentCompletion);
        }

        @Bean PullTaskPullExecutionDispatchResources pullDispatchResources(
                PullTaskPullWavePlanningTransactionService waves,
                PullTaskStickyPullerTransactionService pullers,
                PullTaskPullWaveSettlementTransactionService settlement,
                PullTaskPullerStationContactProcessor contacts,
                PullTaskBatchAddProcessor batch) {
            return new PullTaskPullExecutionDispatchResources(
                    waves, pullers, settlement, contacts, batch);
        }

        @Bean PullTaskPullExecutionProcessor pullExecutionProcessor(
                PullTaskPullExecutionDispatchResources resources,
                PullTaskClosingTransactionService closing) {
            return new PullTaskPullExecutionProcessor(resources, closing);
        }

        @Bean PullTaskUnknownResultResources unknownResultResources(
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper accountMapper) {
            return new PullTaskUnknownResultResources(
                    actionMapper, callMapper, attemptMapper,
                    materialMapper, accountMapper);
        }

        @Bean PullTaskProtocolResultCallbackService protocolResultCallbackService(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullCallParticipantResultService participantResultService,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskProtocolResultCallbackServiceImpl(
                    resources, executionMapper,
                    participantResultService, delayPolicy);
        }

        @Bean PullTaskPullCallParticipantResultService participantResultService(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskPullCallResultCoordination coordination,
                org.springframework.context.ApplicationEventPublisher eventPublisher) {
            return new PullTaskPullCallParticipantResultService(
                    resources, executionMapper, settingMapper, coordination, eventPublisher);
        }

        @Bean PullTaskPullWaveProgressService pullWaveProgressService(
                PullTaskPullWaveMapper waveMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskPullWaveProgressService(waveMapper, executionMapper);
        }

        @Bean PullTaskPullCallResultCoordination pullCallResultCoordination(
                PullTaskStickyPullerTransactionService stickyPullers,
                PullTaskPullWaveProgressService waveProgress) {
            return new PullTaskPullCallResultCoordination(
                    stickyPullers,
                    mock(PullTaskGroupExecutionFailureService.class),
                    waveProgress);
        }

        @Bean PullTaskOperationDelayPolicy operationDelayPolicy() {
            return new PullTaskOperationDelayPolicy(() -> 4_000L);
        }

        @Bean PullTaskPullerInviteDelayPolicy pullerInviteDelayPolicy() {
            return new PullTaskPullerInviteDelayPolicy(() -> 7_000L);
        }

        @Bean PullTaskExecutionStageRouter stageRouter(
                PullTaskLinkValidationProcessor linkProcessor,
                PullTaskManagerJoinProcessor managerJoinProcessor,
                PullTaskManagerAdminProcessor managerAdminProcessor,
                PullTaskManagerPullerContactProcessor managerPullerContactProcessor,
                PullTaskPullerInviteProcessor pullerInviteProcessor,
                PullTaskPullExecutionProcessor pullExecutionProcessor,
                PullTaskMaterialAdminProcessor materialAdminProcessor) {
            return new PullTaskExecutionStageRouter(
                    linkProcessor, managerJoinProcessor, managerAdminProcessor,
                    managerPullerContactProcessor,
                    pullerInviteProcessor, pullExecutionProcessor, materialAdminProcessor);
        }

        @Bean GroupExecutionAccountSelector promoterSelector() {
            GroupExecutionAccountSelector selector = mock(GroupExecutionAccountSelector.class);
            when(selector.findPullTaskAdminPromoterCandidates(
                    7L, GROUP_JID, MANAGER.armadaAccountId())).thenReturn(List.of(
                    new GroupExecutionAccount(
                            PROMOTER.armadaAccountId(), "web", PROMOTER.protocolAccountId(),
                            PROMOTER.wsPhone(), true)));
            return selector;
        }

        @Bean PullTaskManagerAdminProcessor managerAdminProcessor(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupExecutionMapper executionMapper,
                GroupExecutionAccountSelector promoterSelector,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties,
                PullTaskMemberQueryAwaitService memberQueryAwaitService) {
            PullTaskManagerAdminResources resources = new PullTaskManagerAdminResources(
                    executionMapper, promoterSelector, outboxService, properties);
            PullTaskManagerAdminTransactionService transactions =
                    new PullTaskManagerAdminTransactionService(
                            taskMapper, accountMapper, actionMapper,
                            new PullTaskManagerAdminCandidateSelector(), resources);
            return new PullTaskManagerAdminProcessor(transactions, memberQueryAwaitService);
        }

        @Bean PullTaskMaterialAdminProcessor materialAdminProcessor(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                com.armada.platform.protocol.service.ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            PullTaskMaterialAdminResources resources =
                    new PullTaskMaterialAdminResources(
                            executionMapper, lookup, outboxService, properties);
            PullTaskMaterialAdminTransactionService transactions =
                    new PullTaskMaterialAdminTransactionService(
                            taskMapper, accountMapper, materialMapper, resources);
            return new PullTaskMaterialAdminProcessor(transactions);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            PullTaskExecutionDispatchProperties properties =
                    new PullTaskExecutionDispatchProperties();
            properties.setBatchSize(1);
            properties.setLeaseMs(500L);
            properties.setRetryDelayMs(1_000L);
            return properties;
        }

        @Bean PullTaskExecutionDispatchCoordinator coordinator(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskExecutionStageRouter router,
                PullTaskResourceRecoveryTransactionService resourceRecovery,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskExecutionDispatchCoordinator(
                    executionMapper, router, resourceRecovery, properties, "e2e-worker");
        }

        @Bean PullTaskResourceRecoveryTransactionService resourceRecovery(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService lookup,
                PullTaskStationSelectionService stationSelection) {
            PullTaskResourceRecoveryResources resources =
                    new PullTaskResourceRecoveryResources(
                            executionMapper, lookup, stationSelection,
                            mock(com.armada.group.service.GroupExecutionAccountSelector.class),
                            mock(PullTaskAccountActionMapper.class),
                            new PullTaskManagerAdminCandidateSelector());
            return new PullTaskResourceRecoveryTransactionService(
                    taskMapper, settingMapper, accountMapper, resources);
        }
    }
}
