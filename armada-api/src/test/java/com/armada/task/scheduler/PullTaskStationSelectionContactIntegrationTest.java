package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.PullTaskContactSaveResultService;
import com.armada.task.service.impl.PullTaskContactSaveResultServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
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

/** EX-05 使用真实 Mapper XML 验证站台绑定及拉手—站台双向联系人。 */
@SpringJUnitConfig(PullTaskStationSelectionContactIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStationSelectionContactIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskPullCallMapper pullCallMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskStationSelectionService stationSelectionService;
    @Autowired private PullTaskPullerStationContactTransactionService contactService;
    @Autowired private PullTaskContactSaveResultService contactResultService;

    private Long executionId;
    private PullTaskPullCall call;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        PullTaskGroupExecution execution = draft();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution "
                + "SET execution_status=2, stage="
                + PullTaskExecutionStage.PULL_EXECUTION.code()
                + ", version=6, group_jid='120363group@g.us' "
                + "WHERE id=" + executionId);
        PullTaskGroupAccount puller = role(902L, "8613800000902",
                PullTaskGroupAccountRole.PULLER, 1);
        puller.setOccupiedAt(500L);
        groupAccountMapper.insert(puller);
        groupAccountMapper.updateMembership(puller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        call = plannedCall(puller);
        pullCallMapper.insertPlanned(call);
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenAnswer(invocation -> {
                    List<ProtocolPullTaskContactSaveCommandRequest> requests =
                            invocation.getArgument(0);
                    List<String> commandIds = requests.stream()
                            .map(request -> "cmd-station-contact-" + request.actionId())
                            .toList();
                    return new ProtocolCommandOutboxEnqueueResult(
                            "pull-task:100", commandIds, commandIds.size());
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selectedStationIsBoundAndBothContactDirectionsConvergeThroughOutboxCallbacks() {
        ProtocolAccountRef pullerRef = account(902L, "8613800000902");
        ProtocolAccountRef stationRef = account(911L, "8613800000911");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(stationRef));
        when(accountLookup.findActiveProtocolRefs(anyList()))
                .thenReturn(List.of(pullerRef, stationRef));
        PullTaskStationSelection selected = stationSelectionService.select(
                execution(), setting(), call.getId(), 580L);

        assertThat(selected.sufficient()).isTrue();
        assertThat(selected.stations()).singleElement()
                .satisfies(station -> {
                    assertThat(station.getAccountId()).isEqualTo(911L);
                    assertThat(station.getPullCallId()).isEqualTo(call.getId());
                    assertThat(station.getRoleType())
                            .isEqualTo(PullTaskGroupAccountRole.STATION.code());
        });

        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        assertThat(contactService.prepare(firstCandidate, call, "worker-1", 610L))
                .isEqualTo(PullTaskStationContactStepResult.MORE_CONTACTS);
        PullTaskAccountAction first = submittedAction();
        applyCallback(first, PullTaskContactSaveOutcome.FAILED, 620L);

        PullTaskGroupExecution secondCandidate = claim("worker-2", 700L, 1_000L);
        assertThat(contactService.prepare(secondCandidate, call, "worker-2", 710L))
                .isEqualTo(PullTaskStationContactStepResult.MORE_CONTACTS);
        PullTaskAccountAction second = submittedAction();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        applyCallback(second, PullTaskContactSaveOutcome.SUCCESS, 720L);

        PullTaskGroupExecution readyCandidate = claim("worker-3", 800L, 1_100L);
        assertThat(contactService.prepare(readyCandidate, call, "worker-3", 810L))
                .isEqualTo(PullTaskStationContactStepResult.CALL_READY);

        TenantContext.set(7L);
        assertThat(actionMapper.selectByExecutionAndType(
                executionId, PullTaskAccountActionType.SAVE_CONTACT.code()))
                .extracting(PullTaskAccountAction::getActionStatus)
                .containsExactly(PullTaskActionStatus.FAILED.code(),
                        PullTaskActionStatus.SUCCESS.code());
    }

    @Test
    void insufficientStationsPersistNothing() {
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of());

        PullTaskStationSelection selected = stationSelectionService.select(
                execution(), setting(), call.getId(), 580L);

        assertThat(selected.sufficient()).isFalse();
        assertThat(selected.missingCount()).isEqualTo(1);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code())).isEmpty();
    }

    @Test
    void duplicateCandidateFactsStillProduceUniqueStations() {
        ProtocolAccountRef first = account(911L, "8613800000911");
        ProtocolAccountRef second = account(912L, "8613800000912");
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(first, first, second));
        PullTaskStandardSetting setting = setting();
        setting.setStationCountPerCall(2);

        PullTaskStationSelection selected = stationSelectionService.select(
                execution(), setting, call.getId(), 580L);

        assertThat(selected.sufficient()).isTrue();
        assertThat(selected.stations())
                .extracting(PullTaskGroupAccount::getAccountId)
                .containsExactly(911L, 912L);
    }

    @Test
    void frozenSupplementStationIsReusedAndBoundToThePlannedCall() {
        ProtocolAccountRef stationRef = account(911L, "8613800000911");
        PullTaskGroupAccount station = role(
                911L, "8613800000911", PullTaskGroupAccountRole.STATION, 1);
        station.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        station.setSelectionMode(PullTaskSelectionMode.MANUAL.code());
        station.setEntryMode(null);
        groupAccountMapper.insert(station);
        when(accountLookup.findActiveProtocolRefs(List.of(911L)))
                .thenReturn(List.of(stationRef));
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of());

        PullTaskStationSelection selected = stationSelectionService.select(
                execution(), setting(), call.getId(), 580L);

        assertThat(selected.sufficient()).isTrue();
        assertThat(selected.stations()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(station.getId());
            assertThat(saved.getPullCallId()).isEqualTo(call.getId());
            assertThat(saved.getSourceType())
                    .isEqualTo(PullTaskGroupAccountSource.SUPPLEMENT.code());
            assertThat(saved.getEntryMode()).isNull();
        });
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code())).hasSize(1);
    }

    @Test
    void stationIsUniqueWithinOneExecutionButReusableByAnotherExecution() {
        ProtocolAccountRef first = account(911L, "8613800000911");
        ProtocolAccountRef second = account(912L, "8613800000912");
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(first, second));
        PullTaskStationSelection firstSelection = stationSelectionService.select(
                execution(), setting(), call.getId(), 580L);
        PullTaskStationSelection nextCallSelection = stationSelectionService.select(
                execution(), setting(), call.getId() + 1, 590L);
        PullTaskGroupExecution otherExecution = draft();
        otherExecution.setSeq(2);
        otherExecution.setNormalizedLink("chat.whatsapp.com/BBBB");
        otherExecution.setInviteCode("BBBB");
        otherExecution.setSourceLinkLineNo(2);
        otherExecution.setSourceFileIndex(2);
        otherExecution.setSourceFileName("material-2.txt");
        executionMapper.insertDraft(otherExecution);
        PullTaskStationSelection otherGroupSelection = stationSelectionService.select(
                otherExecution, setting(), call.getId() + 2, 600L);

        assertThat(firstSelection.stations().get(0).getAccountId()).isEqualTo(911L);
        assertThat(nextCallSelection.stations().get(0).getAccountId()).isEqualTo(912L);
        assertThat(otherGroupSelection.stations().get(0).getAccountId()).isEqualTo(911L);
    }

    @Test
    void submittedStationContactRemainsSubmittedAndIsNotRepublished() throws SQLException {
        ProtocolAccountRef pullerRef = account(902L, "8613800000902");
        ProtocolAccountRef stationRef = account(911L, "8613800000911");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(stationRef));
        when(accountLookup.findActiveProtocolRefs(anyList()))
                .thenReturn(List.of(pullerRef, stationRef));
        stationSelectionService.select(execution(), setting(), call.getId(), 580L);
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 650L);
        assertThat(contactService.prepare(firstCandidate, call, "worker-1", 610L))
                .isEqualTo(PullTaskStationContactStepResult.MORE_CONTACTS);
        PullTaskAccountAction first = submittedAction();
        execute("UPDATE pull_task_group_execution SET next_run_at=0 WHERE id=" + executionId);

        PullTaskGroupExecution recoveredCandidate = claim("worker-2", 700L, 1_000L);
        assertThat(contactService.prepare(recoveredCandidate, call, "worker-2", 710L))
                .isEqualTo(PullTaskStationContactStepResult.MORE_CONTACTS);

        TenantContext.set(7L);
        PullTaskAccountAction firstAction = actionMapper.selectByExecutionAndType(
                        executionId, PullTaskAccountActionType.SAVE_CONTACT.code())
                .stream()
                .filter(action -> action.getId().equals(first.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(firstAction.getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(firstAction.getCommandId()).isEqualTo(first.getCommandId());
        org.mockito.Mockito.verify(outboxService, org.mockito.Mockito.times(1))
                .enqueuePullTaskContactSaveCommands(anyList());
    }

    private PullTaskAccountAction submittedAction() {
        TenantContext.set(7L);
        return actionMapper.selectByExecutionAndType(
                        executionId, PullTaskAccountActionType.SAVE_CONTACT.code())
                .stream()
                .filter(action -> action.getActionStatus() == PullTaskActionStatus.SUBMITTED.code())
                .findFirst()
                .orElseThrow();
    }

    private void applyCallback(
            PullTaskAccountAction action,
            PullTaskContactSaveOutcome outcome,
            long occurredAt) {
        TenantContext.set(7L);
        PullTaskGroupAccount actor = groupAccountMapper.selectById(action.getActorGroupAccountId());
        assertThat(contactResultService.apply(new PullTaskContactSaveCallback(
                7L, 100L, executionId, action.getId(), actor.getAccountId(),
                "protocol-" + actor.getAccountId(), action.getCommandId(), 1,
                outcome, "CONTACT_RESULT", null, false, occurredAt))).isTrue();
    }

    private PullTaskGroupExecution claim(String owner, long now, long expiresAt) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, expiresAt),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.PULL_EXECUTION.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private PullTaskGroupExecution execution() {
        TenantContext.set(7L);
        return executionMapper.selectByTaskId(100L).get(0);
    }

    private PullTaskPullCall plannedCall(PullTaskGroupAccount puller) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setCallSeq(1);
        row.setPullerGroupAccountId(puller.getId());
        row.setPullerAccountId(puller.getAccountId());
        row.setPlannedMaterialCount(1);
        row.setPlannedStationCount(1);
        row.setIdempotencyKey("pull-task-call:" + executionId + ":1");
        row.setCreatedAt(570L);
        row.setUpdatedAt(570L);
        return row;
    }

    private PullTaskStandardSetting setting() {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setStationGroupId(90L);
        row.setStationCountPerCall(1);
        return row;
    }

    private PullTaskGroupAccount role(
            long accountId, String phone, PullTaskGroupAccountRole role, int seq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
        row.setRoleSeq(seq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(role == PullTaskGroupAccountRole.STATION ? 3 : 2);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static ProtocolAccountRef account(long id, String phone) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB,
                "protocol-" + id, phone);
    }

    private static PullTaskGroupExecution draft() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(100L);
        row.setSeq(1);
        row.setGroupLinkId(9_001L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setInviteCode("AAAA");
        row.setSourceLinkLineNo(1);
        row.setSourceFileIndex(1);
        row.setSourceFileName("material.txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_station_contact_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean
        PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean
        PullTaskPullCallMapper pullCallMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean
        AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean
        ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean
        PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean
        PullTaskStationSelectionService stationSelectionService(
                PullTaskGroupAccountMapper groupAccountMapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskStationSelectionService(groupAccountMapper, accountLookup);
        }

        @Bean
        PullTaskPullerStationContactResources contactResources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup,
                PullTaskPullCallMapper pullCallMapper,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullerStationContactResources(
                    executionMapper, accountLookup, pullCallMapper,
                    outboxService, properties);
        }

        @Bean
        PullTaskPullerStationContactTransactionService contactService(
                PullTaskMapper taskMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullerStationContactResources resources) {
            return new PullTaskPullerStationContactTransactionService(
                    taskMapper, groupAccountMapper, actionMapper, resources);
        }

        @Bean
        PullTaskContactSaveResultService contactResultService(
                PullTaskAccountActionMapper actionMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskContactSaveResultServiceImpl(
                    actionMapper, accountMapper, executionMapper);
        }
    }
}
