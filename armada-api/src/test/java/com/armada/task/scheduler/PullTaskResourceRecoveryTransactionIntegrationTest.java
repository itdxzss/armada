package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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

/** SC-05 使用真实 Mapper XML 验证资源等待、缺口和自动恢复。 */
@SpringJUnitConfig(PullTaskResourceRecoveryTransactionIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskResourceRecoveryTransactionIntegrationTest {

    private static final ProtocolAccountRef MANAGER = account(901L);
    private static final ProtocolAccountRef PULLER = account(902L);
    private static final ProtocolAccountRef STATION = account(903L);

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private GroupExecutionAccountSelector promoterSelector;
    @Autowired private PullTaskResourceRecoveryTransactionService service;

    private long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
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
                + "VALUES (7, 100, 1, 1, 1, 2, 1, 2, 1, 1, 5, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
        PullTaskGroupExecution execution = execution();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stationShortageRefreshesExactGapAndDefersNextProbe() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.STATION, "旧缺口");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of());
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getReasonMessage()).isEqualTo("当前可用站台不足，缺口人数=1");
        assertThat(saved.getNextRunAt()).isEqualTo(2_600L);
    }

    @Test
    void validatedStationRestoresOriginalCheckpointWithoutSelectingItYet() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.STATION, "当前可用站台不足，缺口人数=1");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(STATION));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(saved.getWaitResourceType()).isNull();
        assertThat(saved.getReasonCode()).isNull();
        assertThat(accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code())).isEmpty();
    }

    @Test
    void recoveredResourceWaitMustReacquireParentConcurrencySlot() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.STATION, "当前可用站台不足，缺口人数=1");
        PullTaskGroupExecution running = execution();
        running.setSeq(2);
        running.setGroupLinkId(9_002L);
        running.setNormalizedLink("chat.whatsapp.com/BBBB");
        running.setInviteCode("BBBB");
        running.setSourceLinkLineNo(2);
        running.setSourceFileIndex(2);
        executionMapper.insertDraft(running);
        executionMapper.freezeDraftRows(100L, 550L);
        execute("UPDATE pull_task_group_execution SET execution_status=2, stage="
                + PullTaskExecutionStage.PULL_EXECUTION.code() + " "
                + "WHERE id=" + running.getId());
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(STATION));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getNextRunAt()).isEqualTo(2_600L);
        assertThat(executionMapper.selectById(running.getId()).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    @Test
    void supplementalStationFromAnotherGroupRestoresThePullCheckpoint()
            throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.STATION, "当前可用站台不足，缺口人数=1");
        PullTaskGroupAccount station = new PullTaskGroupAccount();
        station.setTaskId(100L);
        station.setGroupExecutionId(executionId);
        station.setAccountId(905L);
        station.setAccountPhone("8613800000905");
        station.setRoleType(PullTaskGroupAccountRole.STATION.code());
        station.setRoleSeq(1);
        station.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        station.setSelectionMode(PullTaskSelectionMode.MANUAL.code());
        station.setEntryMode(null);
        station.setCreatedAt(100L);
        station.setUpdatedAt(100L);
        accountMapper.insert(station);
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of());
        when(accountLookup.findActiveProtocolRefs(List.of(905L)))
                .thenReturn(List.of(account(905L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(station.getPullCallId()).isNull();
    }

    @Test
    void oneValidatedPullerRestoresOfflineFactAndReleasedLease() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.PULLER, "当前没有可用拉手");
        PullTaskGroupAccount puller = puller();
        accountMapper.insert(puller);
        accountMapper.updateMembership(puller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 510L, 510L);
        accountMapper.markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                "ACCOUNT_OFFLINE", null, 520L);
        accountMapper.releasePuller(puller.getId(), 530L);
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(PULLER));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupAccount savedPuller = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(savedPuller.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(savedPuller.getReleasedAt()).isNull();
        assertThat(executionMapper.selectByTaskId(100L).get(0).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    @Test
    void supplementalPullerFromAnotherGroupCanRestoreItsReleasedLease() throws SQLException {
        waitAt(PullTaskExecutionStage.MANAGER_PULLER_CONTACT,
                PullTaskWaitResourceType.PULLER, "当前没有可用拉手");
        PullTaskGroupAccount puller = puller();
        puller.setAccountId(905L);
        puller.setAccountPhone("8613800000905");
        puller.setSourceType(2);
        puller.setEntryMode(1);
        accountMapper.insert(puller);
        accountMapper.updateMembership(puller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 510L, 510L);
        accountMapper.releasePuller(puller.getId(), 530L);
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of());
        when(accountLookup.findActiveProtocolRefs(List.of(905L)))
                .thenReturn(List.of(account(905L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupAccount savedPuller = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(savedPuller.getReleasedAt()).isNull();
        assertThat(executionMapper.selectByTaskId(100L).get(0).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    @Test
    void activeManagerCandidateRestoresInitialManagerJoinWait() throws SQLException {
        waitAt(PullTaskExecutionStage.MANAGER_JOIN,
                PullTaskWaitResourceType.MANAGER, "当前没有可用管理员");
        when(accountLookup.findRandomOnlineNormalByGroupId(88L))
                .thenReturn(Optional.of(MANAGER));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
    }

    @Test
    void orderedPromoterCandidateRestoresManagerAdminWaitAtSameStage() throws SQLException {
        waitAt(PullTaskExecutionStage.MANAGER_ADMIN,
                PullTaskWaitResourceType.MANAGER, "当前没有在线的我方群主或管理员");
        PullTaskGroupAccount manager = new PullTaskGroupAccount();
        manager.setTaskId(100L);
        manager.setGroupExecutionId(executionId);
        manager.setAccountId(901L);
        manager.setAccountPhone("8613800000901");
        manager.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        manager.setRoleSeq(1);
        manager.setSourceType(1);
        manager.setSelectionMode(1);
        manager.setEntryMode(1);
        manager.setCreatedAt(100L);
        manager.setUpdatedAt(100L);
        accountMapper.insert(manager);
        accountMapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        GroupExecutionAccount promoter = new GroupExecutionAccount(
                906L, "web", "promoter-906", "8613800000906", true);
        when(accountLookup.findActiveProtocolRefs(List.of(901L))).thenReturn(List.of(MANAGER));
        when(promoterSelector.findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 901L)).thenReturn(List.of(promoter));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(executionId);
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        verify(promoterSelector).findPullTaskAdminPromoterCandidates(
                7L, "120363group@g.us", 901L);
    }

    @Test
    void unexpiredRiskCooldownDoesNotResumeEvenWhenAccountIsOnline() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.PULLER, "当前没有可用拉手");
        PullTaskGroupAccount puller = puller();
        accountMapper.insert(puller);
        accountMapper.updateMembership(puller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 510L, 510L);
        accountMapper.markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                "RATE_LIMITED", 9_999L, 520L);
        accountMapper.releasePuller(puller.getId(), 530L);
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(PULLER));
        PullTaskGroupExecution candidate = claim("worker-1", 600L);

        assertThat(service.recover(candidate, "worker-1", 600L, 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskGroupAccount savedPuller = accountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(savedPuller.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.RISK_COOLDOWN.code());
        assertThat(savedPuller.getReleasedAt()).isNotNull();
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNextRunAt())
                .isEqualTo(2_600L);
    }

    @Test
    void manuallyPausedResourceWaitIsNotClaimedForAutomaticRecovery() throws SQLException {
        waitAt(PullTaskExecutionStage.PULL_EXECUTION,
                PullTaskWaitResourceType.STATION, "当前可用站台不足，缺口人数=1");
        execute("UPDATE pull_task_group_execution SET manual_paused=1 WHERE id=" + executionId);
        TenantContext.clear();

        int claimed = executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(
                        1, 600L, "worker-1", 1_100L),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        List.of(PullTaskExecutionStage.PULL_EXECUTION.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));

        assertThat(claimed).isZero();
        assertThat(executionMapper.selectClaimed("worker-1", 600L)).isEmpty();
    }

    @Test
    void pendingManagerApprovalIsNotClaimedForAutomaticResourceRecovery() throws SQLException {
        waitAt(PullTaskExecutionStage.MANAGER_JOIN,
                PullTaskWaitResourceType.APPROVAL, "管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停");
        TenantContext.clear();

        int claimed = executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(
                        1, 600L, "worker-1", 1_100L),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        List.of(PullTaskExecutionStage.MANAGER_JOIN.code()),
                        List.of(
                                PullTaskWaitResourceType.MANAGER.code(),
                                PullTaskWaitResourceType.PULLER.code(),
                                PullTaskWaitResourceType.STATION.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));

        assertThat(claimed).isZero();
        assertThat(executionMapper.selectClaimed("worker-1", 600L)).isEmpty();
    }

    private void waitAt(
            PullTaskExecutionStage stage,
            PullTaskWaitResourceType resourceType,
            String reasonMessage) throws SQLException {
        execute("UPDATE pull_task_group_execution SET execution_status=3, stage="
                + stage.code() + ", version=6, group_jid='120363group@g.us', "
                + "wait_resource_type=" + resourceType.code()
                + ", reason_code='WAITING', reason_message='" + reasonMessage
                + "', next_run_at=0 WHERE id=" + executionId);
    }

    private PullTaskGroupExecution claim(String owner, long now) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, now + 500L),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        List.of(PullTaskExecutionStage.MANAGER_JOIN.code(),
                                PullTaskExecutionStage.MANAGER_ADMIN.code(),
                                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                                PullTaskExecutionStage.PULLER_INVITE.code(),
                                PullTaskExecutionStage.PULL_EXECUTION.code(),
                                PullTaskExecutionStage.MATERIAL_ADMIN.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private PullTaskGroupExecution execution() {
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

    private PullTaskGroupAccount puller() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(PULLER.armadaAccountId());
        row.setAccountPhone(PULLER.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(500L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "account-" + id, "8613800000" + id);
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

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_resource_recovery_test");
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
                    "mapper/task/PullTaskGroupAccountMapper.xml");
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

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean GroupExecutionAccountSelector promoterSelector() {
            return mock(GroupExecutionAccountSelector.class);
        }

        @Bean PullTaskStationSelectionService stationSelection(
                PullTaskGroupAccountMapper mapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStationSelectionService(mapper, lookup);
        }

        @Bean PullTaskResourceRecoveryResources resources(
                PullTaskGroupExecutionMapper mapper,
                AccountProtocolLookupService lookup,
                PullTaskStationSelectionService stationSelection,
                GroupExecutionAccountSelector promoterSelector,
                PullTaskAccountActionMapper actionMapper,
                PullTaskManagerAdminCandidateSelector candidateSelector) {
            return new PullTaskResourceRecoveryResources(
                    mapper, lookup, stationSelection, promoterSelector,
                    actionMapper, candidateSelector);
        }

        @Bean PullTaskAccountActionMapper actionMapper() {
            return mock(PullTaskAccountActionMapper.class);
        }

        @Bean PullTaskManagerAdminCandidateSelector managerAdminCandidateSelector() {
            return new PullTaskManagerAdminCandidateSelector();
        }

        @Bean PullTaskResourceRecoveryTransactionService service(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper accountMapper,
                PullTaskResourceRecoveryResources resources) {
            return new PullTaskResourceRecoveryTransactionService(
                    taskMapper, settingMapper, accountMapper, resources);
        }
    }
}
