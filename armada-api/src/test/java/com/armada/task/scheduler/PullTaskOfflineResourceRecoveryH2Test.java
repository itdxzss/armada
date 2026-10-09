package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.AccountCreatorReservation;
import com.armada.account.model.AccountRoleAvailability;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.impl.AccountProtocolLookupServiceImpl;
import com.armada.account.takeover.AccountAutoTakeoverProperties;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
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
import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureParticipants;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureResources;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureServiceImpl;
import com.armada.task.service.impl.PullTaskGroupRetryService;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.service.impl.PullTaskPullerAccountStateServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实角色、粘性指针、租约和终止 SQL 验证离线恢复的同事务边界（G30–G34）。 */
@SpringJUnitConfig(PullTaskOfflineResourceRecoveryH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class PullTaskOfflineResourceRecoveryH2Test {

    private static final long NOW = 100_000L;
    private static final long EXECUTION_ID = 200L;
    private static final String OWNER = "offline-recovery-worker";
    private static final String EXECUTION_MAPPER = PullTaskGroupExecutionMapper.class.getName() + ".";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private PullTaskGroupAccountMapper roles;
    @Autowired private AccountMapper accountMapper;
    @Autowired private AccountProtocolLookupService accounts;
    @Autowired private AccountOnlineCommandService online;
    @Autowired private GroupExecutionAccountSelector promoters;
    @Autowired private PullTaskOfflineRoleWaitProperties properties;
    @Autowired private PullTaskResourceRecoveryTransactionService recovery;
    @Autowired private PullTaskManagerPullerContactTransactionService contacts;
    @Autowired private PullTaskPullerInviteTransactionService invites;
    @Autowired private PullTaskStickyPullerTransactionService sticky;
    @Autowired private PullTaskPullCallMapper calls;
    @Autowired private ProtocolCommandOutboxService outbox;
    @Autowired private SqlObservations sql;
    @Autowired private ExpirationBoundary boundary;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accounts, online, promoters, outbox);
        properties.setEnabled(true);
        properties.setReplaceableGraceMs(30_000L);
        properties.setCreatorGraceMs(180_000L);
        boundary.reset();
        sql.clear();
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc.update("INSERT INTO pull_task (id,tenant_id,task_type,task_name,mode,status,config_json,"
                + "created_at,updated_at) VALUES (100,7,'STANDARD','task','NORMAL_LINK','EXECUTING','{}',100,100)");
        jdbc.update("INSERT INTO pull_task_standard_setting (tenant_id,task_id,auto_start,"
                + "material_admin_timing,pull_count_min,pull_count_max,pull_interval_seconds,puller_count_per_group,"
                + "station_count_per_call,concurrent_group_count,puller_risk_minutes,required_manager_count,"
                + "manager_group_id,puller_group_id,station_group_id,manager_group_name,puller_group_name,"
                + "station_group_name,created_at,updated_at) VALUES (7,100,1,1,1,2,1,2,1,1,5,1,88,89,90,"
                + "'manager','puller','station',100,100)");
        jdbc.update("INSERT INTO pull_task_group_execution (id,tenant_id,task_id,seq,source_file_index,"
                + "source_file_name,execution_status,stage,wait_resource_type,group_jid,reason_code,next_run_at,"
                + "version,lock_owner,lock_expires_at,created_at,updated_at) VALUES (200,7,100,1,1,'members.txt',"
                + "3,?,?, 'group@g.us','ACCOUNT_NOT_ONLINE',0,6,?, ?,100,100)",
                PullTaskExecutionStage.PULL_EXECUTION.code(), PullTaskWaitResourceType.PULLER.code(),
                OWNER, NOW + 5_000L);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"PASTED_LINK,MANAGER_PULLER_CONTACT", "DIRECT_LINK,DIRECT_PULLER_JOIN",
            "SIMPLE_NEW_GROUP,DIRECT_PULLER_JOIN"})
    void expiredStickyRoleCommitsRemovalAndEntryWithoutChangingPlannedWave(String mode, String targetStage) {
        jdbc.update("UPDATE pull_task SET creation_mode=? WHERE id=100", mode);
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));
        PullTaskGroupExecution candidate = candidate();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        PullTaskGroupExecution saved = saved();
        assertThat(saved.getVersion()).isEqualTo(candidate.getVersion() + 2);
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.valueOf(targetStage).code());
        assertThat(saved.getActivePullerGroupAccountId()).isNull();
        assertThat(saved.getPullerAssignmentSeq()).isEqualTo(3L);
        assertThat(saved.getActivePullWaveId()).isEqualTo(501L);
        assertThat(saved.getNextRunAt()).isZero();
        assertRemoved(old);
        assertThat(boundary.events).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT wave_status FROM pull_task_pull_wave WHERE id=501", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_status FROM pull_task_pull_call WHERE id=601", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT puller_group_account_id FROM pull_task_pull_call WHERE id=601", Long.class)).isEqualTo(old.getId());
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM pull_task_pull_call_member_attempt WHERE id=701", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT puller_assignment_seq FROM pull_task_pull_call_member_attempt WHERE id=701", Long.class)).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"execution_status=2", "stage=2", "manual_paused=1", "lock_owner='another-worker'",
            "lock_expires_at=100000"})
    void invalidReReadRollsBackRoleAndStickyPointerTogether(String change) {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));
        PullTaskGroupExecution candidate = candidate();
        boundary.change = change;

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.LOST);

        assertThat(boundary.events).isEqualTo(1);
        assertThat(boundary.observedTransaction).isTrue();
        PullTaskGroupExecution saved = saved();
        assertThat(saved.getVersion()).isEqualTo(candidate.getVersion());
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getStage()).isEqualTo(candidate.getStage());
        assertThat(saved.getManualPaused()).isZero();
        assertThat(saved.getLockOwner()).isEqualTo(OWNER);
        assertThat(saved.getLockExpiresAt()).isEqualTo(NOW + 5_000L);
        assertThat(saved.getActivePullerGroupAccountId()).isEqualTo(old.getId());
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        assertThat(roles.selectById(old.getId()).getUnavailableReasonCode()).isEqualTo("ACCOUNT_NOT_ONLINE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASTED_LINK", "DIRECT_LINK", "SIMPLE_NEW_GROUP"})
    void automaticEntryActuallyInsertsReplacementAndRebindsTheExistingPlannedCall(String mode) {
        jdbc.update("UPDATE pull_task SET creation_mode=? WHERE id=100", mode);
        jdbc.update("UPDATE pull_task_standard_setting SET puller_count_per_group=2 WHERE task_id=100");
        jdbc.update("UPDATE pull_task_group_execution SET invite_code='AAAA',normalized_link='chat.whatsapp.com/AAAA' WHERE id=200");
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        PullTaskGroupAccount removed = offlinePuller(906L, 2);
        roles.markUnavailable(removed.getId(), PullTaskGroupAccountAvailability.REMOVED.code(), "ACCOUNT_UNBOUND", null, NOW - 1L);
        activePlan(old);
        PullTaskGroupAccount manager = role(905L, PullTaskGroupAccountRole.MANAGER, 1);
        jdbc.update("UPDATE pull_task_group_account SET admin_status=? WHERE id=?",
                PullTaskGroupAccountAdminStatus.SUCCESS.code(), manager.getId());
        jdbc.update("INSERT INTO account (id,tenant_id,ws_phone,protocol_account_id) VALUES (902,7,'phone-902','protocol-902')");
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));
        when(accounts.findEligiblePullerProtocolRefs(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.contains(902L) ? List.of(protocol(902L)) : List.of();
        });
        when(accounts.findEligibleManagerProtocolRefs(anyList())).thenReturn(List.of(protocol(905L)));
        when(accounts.findActiveProtocolRefs(anyList())).thenAnswer(invocation -> onlineRefs(invocation.getArgument(0)));
        when(accounts.findOnlineProtocolRefs(anyList())).thenAnswer(invocation -> onlineRefs(invocation.getArgument(0)));
        when(outbox.enqueuePullTaskContactSaveCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task:100", List.of("contact-replacement"), 1));
        when(outbox.enqueuePullTaskGroupJoinCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task:100", List.of("join-replacement"), 1));
        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        // 重新认领后调用现有入口；新角色由生产 ensurePullers/insertPuller 真正写入。
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner=?,lock_expires_at=? WHERE id=200", OWNER, NOW + 5_000L);
        PullTaskGroupExecution entry = candidate();
        PullTaskExecutionDispatchResult prepared = "PASTED_LINK".equals(mode)
                ? contacts.prepare(entry, OWNER, NOW + 1L) : invites.prepare(entry, OWNER, NOW + 1L);
        assertThat(prepared).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        List<PullTaskGroupAccount> pullers = roles.selectByExecutionAndRole(EXECUTION_ID, PullTaskGroupAccountRole.PULLER.code());
        assertThat(pullers).hasSize(3);
        PullTaskGroupAccount replacement = pullers.stream().filter(row -> row.getAccountId().equals(902L)).findFirst().orElseThrow();
        assertThat(replacement.getRoleSeq()).isEqualTo(3);
        assertThat(replacement.getReleasedAt()).isNull();
        assertThat(replacement.getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertRemoved(old);
        assertThat(roles.selectById(removed.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        assertThat(saved().getActivePullWaveId()).isEqualTo(501L);
        assertThat(calls.selectByExecution(EXECUTION_ID)).singleElement().satisfies(call -> {
            assertThat(call.getId()).isEqualTo(601L);
            assertThat(call.getPullerGroupAccountId()).isEqualTo(old.getId());
            assertThat(call.getCallStatus()).isEqualTo(1);
        });

        // 以真实已确认入群事实为下一阶段输入，验证既有粘性服务重绑原计划而非创建新波次。
        jdbc.update("UPDATE pull_task_group_account SET membership_status=2 WHERE id=?", replacement.getId());
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2,stage=?,lock_owner=?,lock_expires_at=? WHERE id=200",
                PullTaskExecutionStage.PULL_EXECUTION.code(), OWNER, NOW + 5_000L);
        PullTaskStickyPullerSelection selection = sticky.bindForDispatch(candidate(),
                calls.selectByExecution(EXECUTION_ID).get(0), OWNER, NOW + 2L);
        assertThat(selection.ready()).isTrue();
        assertThat(selection.role().getId()).isEqualTo(replacement.getId());
        assertThat(selection.assignmentSeq()).isEqualTo(4L);
        assertThat(saved().getActivePullWaveId()).isEqualTo(501L);
        assertThat(saved().getActivePullerGroupAccountId()).isEqualTo(replacement.getId());
        assertThat(calls.selectByExecution(EXECUTION_ID)).singleElement().satisfies(call -> {
            assertThat(call.getId()).isEqualTo(601L);
            assertThat(call.getPullerGroupAccountId()).isEqualTo(replacement.getId());
            assertThat(call.getPullerAssignmentSeq()).isEqualTo(4L);
            assertThat(call.getCallStatus()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT puller_group_account_id FROM pull_task_pull_call_member_attempt WHERE id=701", Long.class))
                .isEqualTo(replacement.getId());
        assertThat(jdbc.queryForObject("SELECT puller_assignment_seq FROM pull_task_pull_call_member_attempt WHERE id=701", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM pull_task_pull_call_member_attempt WHERE id=701", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiringNonStickyRoleDoesNotAdvanceAnExtraVersion() {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        jdbc.update("UPDATE pull_task_group_execution SET active_puller_group_account_id=999,puller_assignment_seq=3 WHERE id=200");
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        PullTaskGroupExecution candidate = candidate();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertRemoved(old);
        assertThat(saved().getVersion()).isEqualTo(candidate.getVersion() + 1);
        assertThat(saved().getActivePullerGroupAccountId()).isEqualTo(999L);
        assertThat(saved().getReasonCode()).isEqualTo("PULLER_UNAVAILABLE");
        assertThat(saved().getNextRunAt()).isEqualTo(NOW + 30_000L);
    }

    @Test
    void nonStickyExpirationReReadsPauseInsteadOfUsingMyBatisLocalCache() {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        PullTaskGroupExecution candidate = candidate();
        boundary.change = "manual_paused=1";

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.LOST);

        assertThat(boundary.events).isEqualTo(1);
        assertThat(saved().getManualPaused()).isZero();
        assertThat(saved().getVersion()).isEqualTo(candidate.getVersion());
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
    }

    @Test
    void disabledFeatureDoesNotExpireOrReadExecutionAgain() {
        properties.setEnabled(false);
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        PullTaskGroupExecution candidate = candidate();
        sql.clear();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(sql.count(EXECUTION_MAPPER + "selectById")).isZero();
        assertThat(sql.count(EXECUTION_MAPPER + "selectByIdForUpdate")).isZero();
        assertThat(boundary.events).isZero();
        verify(accounts, never()).findRoleAvailability(anyCollection());
        assertThat(saved().getVersion()).isEqualTo(candidate.getVersion() + 1);
        assertThat(saved().getActivePullerGroupAccountId()).isEqualTo(old.getId());
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
    }

    @Test
    void graceWaitUsesEarliestDeadlineAndDoesNotReadExecutionAgain() {
        offlinePuller(901L, 1);
        offlinePuller(902L, 2);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(
                901L, recovering(901L, 80_000L), 902L, recovering(902L, 75_000L)));
        PullTaskGroupExecution candidate = candidate();
        sql.clear();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(sql.count(EXECUTION_MAPPER + "selectById")).isZero();
        assertThat(boundary.events).isZero();
        assertThat(saved().getNextRunAt()).isEqualTo(105_000L);
        assertThat(saved().getReasonCode()).isEqualTo("ACCOUNT_NOT_ONLINE");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 5})
    void inFlightJoinKeepsItsSlotEvenBeyondGrace(int membership) {
        jdbc.update("UPDATE pull_task_standard_setting SET puller_count_per_group=1 WHERE task_id=100");
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=? WHERE id=?", membership, old.getId());
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 1L)));
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(boundary.events).isZero();
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        assertThat(roles.selectById(old.getId()).getMembershipStatus()).isEqualTo(membership);
    }

    @Test
    void terminalAccountExpiresBeforeGraceAndNeverReusesHistoricalAccount() {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L,
                new AccountRoleAvailability(901L, AccountRoleAvailability.Kind.TERMINAL, NOW, 2, null)));
        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertRemoved(old);
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner=?,lock_expires_at=? WHERE id=200", OWNER, NOW + 5_000L);
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(901L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(saved().getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(saved().getReasonCode()).isEqualTo("PULLER_UNAVAILABLE");
        assertRemoved(old);
    }

    @Test
    void onlineOriginalRoleRecoversInsideGraceWithoutExpiration() {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(901L)));
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L,
                new AccountRoleAvailability(901L, AccountRoleAvailability.Kind.ONLINE, null, 1, null)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(boundary.events).isZero();
        assertThat(saved().getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(saved().getActivePullerGroupAccountId()).isEqualTo(old.getId());
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"desired-offline", "tripped-breaker"})
    void onlineTerminalAccountIsExpiredBeforeLegacyEligibilityCanRestoreIt(String terminalFact) {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        terminalOnlinePullerLookup(terminalFact);

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertRemoved(old);
        assertThat(saved().getActivePullerGroupAccountId()).isNull();
        assertThat(saved().getReasonCode()).isEqualTo("PULLER_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"desired-offline", "tripped-breaker"})
    void terminalOnlinePullerWithPendingJoinIsNotRestored(String terminalFact) {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=? WHERE id=?",
                PullTaskGroupAccountMembershipStatus.JOINING.code(), old.getId());
        terminalOnlinePullerLookup(terminalFact);

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(boundary.events).isZero();
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        assertThat(roles.selectById(old.getId()).getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());
        assertThat(saved().getReasonCode()).isEqualTo("ACCOUNT_NOT_ONLINE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"desired-offline", "tripped-breaker"})
    void terminalOnlineReleasedPullerIsNotReoccupied(String terminalFact) {
        PullTaskGroupAccount old = role(901L, PullTaskGroupAccountRole.PULLER, 1);
        jdbc.update("UPDATE pull_task_group_account SET released_at=90000 WHERE id=?", old.getId());
        terminalOnlinePullerLookup(terminalFact);

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(boundary.events).isZero();
        assertThat(roles.selectById(old.getId()).getReleasedAt()).isEqualTo(90_000L);
        assertThat(saved().getReasonCode()).isEqualTo("PULLER_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledFeatureKeepsLegacyRestoreAndReoccupyWithoutTerminalLookup(boolean released) {
        properties.setEnabled(false);
        PullTaskGroupAccount old = released ? role(901L, PullTaskGroupAccountRole.PULLER, 1) : offlinePuller(901L, 1);
        if (released) {
            jdbc.update("UPDATE pull_task_group_account SET released_at=90000 WHERE id=?", old.getId());
        }
        terminalOnlinePullerLookup("tripped-breaker");

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verify(accounts, never()).findRoleAvailability(anyCollection());
        assertThat(roles.selectById(old.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(roles.selectById(old.getId()).getReleasedAt()).isNull();
    }

    @Test
    void freedSlotWithNewAccountReturnsToEntryEvenWithoutAnOfflineRole() {
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(saved().getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(roles.selectByExecutionAndRole(EXECUTION_ID, PullTaskGroupAccountRole.PULLER.code())).isEmpty();
    }

    @Test
    void expiredStickyRoleCommitsWhenParentConcurrencySlotIsBusy() {
        PullTaskGroupAccount old = offlinePuller(901L, 1);
        activePlan(old);
        jdbc.update("INSERT INTO pull_task_group_execution (id,tenant_id,task_id,seq,source_file_index,source_file_name,"
                + "execution_status,stage,created_at,updated_at) VALUES (201,7,100,2,2,'other.txt',2,?,100,100)",
                PullTaskExecutionStage.PULL_EXECUTION.code());
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 70_000L)));
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenReturn(List.of(protocol(902L)));
        PullTaskGroupExecution candidate = candidate();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertRemoved(old);
        assertThat(saved().getVersion()).isEqualTo(candidate.getVersion() + 2);
        assertThat(saved().getActivePullerGroupAccountId()).isNull();
        assertThat(saved().getReasonCode()).isEqualTo("EXECUTION_SLOT_UNAVAILABLE");
        assertThat(saved().getNextRunAt()).isEqualTo(NOW + 30_000L);
    }

    @Test
    void managerAdminCreatorGiveUpCommitsExactlyOneTerminalTransition() {
        managerAdmin("NEW_GROUP");
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(903L,
                new AccountRoleAvailability(903L, AccountRoleAvailability.Kind.TERMINAL, 1L, 2, null)));
        PullTaskGroupExecution candidate = candidate();
        sql.clear();

        assertThat(recovery.recover(candidate, OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.FAILED);

        assertThat(sql.count(EXECUTION_MAPPER + "transitionTerminal")).isEqualTo(1);
        assertThat(sql.count(EXECUTION_MAPPER + "transitionClaimed")).isZero();
        PullTaskGroupExecution saved = saved();
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.FAILED.code());
        assertThat(saved.getReasonCode()).isEqualTo("GROUP_CREATOR_OFFLINE");
        assertThat(saved.getVersion()).isEqualTo(candidate.getVersion() + 1);
        assertThat(saved.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void managerAdminReservedCreatorWaitCommitsBeforeRequestingRecovery() {
        managerAdmin("NEW_GROUP");
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(903L,
                new AccountRoleAvailability(903L, AccountRoleAvailability.Kind.RECOVERING, 10_000L, 2,
                        new AccountCreatorReservation(7L, "RESERVED", 100L, EXECUTION_ID))));
        when(online.reonlineReservedCreator(903L, 100L, EXECUTION_ID)).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution WHERE id=200", String.class))
                    .isEqualTo("GROUP_CREATOR_RECONNECTING");
            assertThat(jdbc.queryForObject("SELECT lock_owner FROM pull_task_group_execution WHERE id=200", String.class)).isNull();
            return null;
        });

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(saved().getNextRunAt()).isEqualTo(190_000L);
        assertThat(saved().getReasonCode()).isEqualTo("GROUP_CREATOR_RECONNECTING");
        verify(online).reonlineReservedCreator(903L, 100L, EXECUTION_ID);
    }

    @Test
    void ordinaryLinkManagerAdminKeepsExistingWaitReason() {
        managerAdmin("PASTED_LINK");

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(accounts).findRoleAvailability(List.of(901L));
        assertThat(saved().getReasonCode()).isEqualTo("MANAGER_ADMIN_ACTOR_UNAVAILABLE");
    }

    private void terminalOnlinePullerLookup(String terminalFact) {
        jdbc.execute("ALTER TABLE account ADD COLUMN account_group_id BIGINT DEFAULT 89");
        jdbc.execute("ALTER TABLE account ADD COLUMN protocol_id VARCHAR(32) DEFAULT 'WEB'");
        jdbc.execute("ALTER TABLE account ADD COLUMN deleted_at BIGINT");
        jdbc.execute("CREATE TABLE account_state (account_id BIGINT PRIMARY KEY,tenant_id BIGINT,account_state INT,"
                + "login_state INT,desired_login_state INT,mute_status INT,offline_since BIGINT)");
        jdbc.execute("CREATE TABLE account_takeover_breaker (account_id BIGINT PRIMARY KEY,tenant_id BIGINT,tripped_at BIGINT)");
        jdbc.update("INSERT INTO account_state VALUES (901,7,2,1,?,NULL,NULL)",
                "desired-offline".equals(terminalFact) ? 2 : 1);
        if ("tripped-breaker".equals(terminalFact)) {
            jdbc.update("INSERT INTO account_takeover_breaker VALUES (901,7,99000)");
        }
        AccountProtocolLookupService realAccounts = new AccountProtocolLookupServiceImpl(
                accountMapper, new AccountAutoTakeoverProperties());
        // 真实账号 SQL 证明旧资格查询仍包含此号，但新角色可用性明确为 TERMINAL。
        assertThat(realAccounts.findOnlineEligiblePullersByGroupId(89L)).extracting(ProtocolAccountRef::armadaAccountId)
                .containsExactly(901L);
        assertThat(realAccounts.findRoleAvailability(List.of(901L)).get(901L).kind()).isEqualTo(AccountRoleAvailability.Kind.TERMINAL);
        when(accounts.findOnlineEligiblePullersByGroupId(89L)).thenAnswer(invocation -> realAccounts.findOnlineEligiblePullersByGroupId(89L));
        when(accounts.findEligiblePullerProtocolRefs(anyList())).thenAnswer(invocation ->
                realAccounts.findEligiblePullerProtocolRefs(invocation.getArgument(0)));
        when(accounts.findRoleAvailability(anyCollection())).thenAnswer(invocation ->
                realAccounts.findRoleAvailability(invocation.getArgument(0)));
    }

    private void managerAdmin(String mode) {
        jdbc.update("UPDATE pull_task SET creation_mode=? WHERE id=100", mode);
        jdbc.update("UPDATE pull_task_group_execution SET stage=?,wait_resource_type=? WHERE id=200",
                PullTaskExecutionStage.MANAGER_ADMIN.code(), PullTaskWaitResourceType.MANAGER.code());
        role(901L, PullTaskGroupAccountRole.MANAGER, 1);
        role(903L, PullTaskGroupAccountRole.PROMOTER, 1);
        when(accounts.findEligibleManagerProtocolRefs(List.of(901L))).thenReturn(List.of(protocol(901L)));
        when(promoters.findPullTaskAdminPromoterCandidates(7L, "group@g.us", 901L)).thenReturn(List.of());
    }

    private PullTaskGroupAccount offlinePuller(long accountId, int seq) {
        PullTaskGroupAccount row = role(accountId, PullTaskGroupAccountRole.PULLER, seq);
        roles.markUnavailable(row.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(), "ACCOUNT_NOT_ONLINE", null, 60_000L);
        return roles.selectById(row.getId());
    }

    private PullTaskGroupAccount role(long accountId, PullTaskGroupAccountRole kind, int seq) {
        jdbc.update("INSERT INTO account (id,tenant_id,ws_phone,protocol_account_id) VALUES (?,7,?,?)",
                accountId, "phone-" + accountId, "protocol-" + accountId);
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setAccountId(accountId);
        row.setAccountPhone("phone-" + accountId);
        row.setRoleType(kind.code());
        row.setRoleSeq(seq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(100L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        roles.insert(row);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=? WHERE id=?",
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), row.getId());
        return roles.selectById(row.getId());
    }

    private void activePlan(PullTaskGroupAccount old) {
        jdbc.update("UPDATE pull_task_group_execution SET active_puller_group_account_id=?,puller_assignment_seq=3,"
                + "active_pull_wave_id=501 WHERE id=200", old.getId());
        jdbc.update("INSERT INTO pull_task_pull_wave (id,tenant_id,task_id,group_execution_id,wave_no,wave_type,"
                + "wave_status,planned_call_count,created_at,updated_at) VALUES (501,7,100,200,1,1,1,1,100,100)");
        jdbc.update("INSERT INTO pull_task_pull_call (id,tenant_id,task_id,group_execution_id,pull_wave_id,call_seq,"
                + "wave_call_seq,puller_group_account_id,puller_account_id,puller_assignment_seq,planned_material_count,"
                + "planned_station_count,idempotency_key,created_at,updated_at) VALUES (601,7,100,200,501,1,1,?,?,3,1,0,'plan-601',100,100)",
                old.getId(), old.getAccountId());
        jdbc.update("INSERT INTO pull_task_pull_call_member_attempt (id,tenant_id,task_id,group_execution_id,pull_call_id,"
                + "pull_wave_id,participant_type,participant_ref_id,target_phone,puller_group_account_id,puller_assignment_seq,"
                + "attempt_no,created_at,updated_at) VALUES (701,7,100,200,601,501,1,801,'target-801',?,3,1,100,100)", old.getId());
    }

    private PullTaskGroupExecution candidate() {
        TenantContext.set(7L);
        return executions.selectById(EXECUTION_ID);
    }

    private PullTaskGroupExecution saved() {
        return candidate();
    }

    private void assertRemoved(PullTaskGroupAccount role) {
        PullTaskGroupAccount saved = roles.selectById(role.getId());
        assertThat(saved.getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        assertThat(saved.getUnavailableReasonCode()).isEqualTo("PULLER_OFFLINE_TIMEOUT");
    }

    private static AccountRoleAvailability recovering(long accountId, long offlineSince) {
        return new AccountRoleAvailability(accountId, AccountRoleAvailability.Kind.RECOVERING, offlineSince, 2, null);
    }

    private static ProtocolAccountRef protocol(long accountId) {
        return new ProtocolAccountRef(accountId, ProtocolBackend.WEB, "protocol-" + accountId, "phone-" + accountId);
    }

    private static List<ProtocolAccountRef> onlineRefs(List<Long> ids) {
        return List.of(902L, 905L).stream().filter(ids::contains).map(PullTaskOfflineResourceRecoveryH2Test::protocol).toList();
    }

    /** 只观察真实 MyBatis 执行，不改 SQL、参数或返回结果。 */
    @Intercepts({@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
    static class SqlObservations implements Interceptor {
        private final Map<String, Integer> counts = new HashMap<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            counts.merge(((MappedStatement) invocation.getArgs()[0]).getId(), 1, Integer::sum);
            return invocation.proceed();
        }

        int count(String statement) {
            return counts.getOrDefault(statement, 0);
        }

        void clear() {
            counts.clear();
        }
    }

    /** 在生产事件边界上制造重读前状态变化，全部写入仍属于真实外层事务。 */
    static class ExpirationBoundary {
        private final JdbcTemplate jdbc;
        private String change;
        private int events;
        private boolean observedTransaction;

        ExpirationBoundary(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @EventListener
        public void onExpiration(PullTaskPullerUnavailableEvent event) {
            events++;
            observedTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            if (change != null) {
                jdbc.update("UPDATE pull_task_group_execution SET " + change + " WHERE id=200");
            }
        }

        void reset() {
            change = null;
            events = 0;
            observedTransaction = false;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MyBatisConfig.class, PullTaskResourceRecoveryTransactionService.class,
            PullTaskStickyPullerTransactionService.class, PullTaskPullerAccountStateServiceImpl.class,
            com.armada.task.service.impl.PullTaskPullerAccountStateResources.class,
            PullTaskCreatorOfflineGate.class, PullTaskGroupExecutionFailureServiceImpl.class,
            PullTaskParentCompletionService.class, PullTaskManagerPullerContactTransactionService.class,
            PullTaskPullerInviteTransactionService.class})
    static class TestConfig {
        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("offline_resource_recovery");
        }

        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean SqlObservations observations() { return new SqlObservations(); }
        @Bean ExpirationBoundary boundary(JdbcTemplate jdbc) { return new ExpirationBoundary(jdbc); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }

        @Bean SqlSessionFactory factory(DataSource source, MybatisPlusInterceptor tenant, SqlObservations observations) throws Exception {
            SqlSessionFactory factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source, tenant,
                    "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml", "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml", "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml", "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/account/AccountMapper.xml");
            factory.getConfiguration().addInterceptor(observations);
            return factory;
        }

        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean PullTaskMapper tasks(SqlSessionTemplate session) { return session.getMapper(PullTaskMapper.class); }
        @Bean PullTaskStandardSettingMapper settings(SqlSessionTemplate session) { return session.getMapper(PullTaskStandardSettingMapper.class); }
        @Bean PullTaskGroupExecutionMapper executions(SqlSessionTemplate session) { return session.getMapper(PullTaskGroupExecutionMapper.class); }
        @Bean PullTaskGroupAccountMapper roles(SqlSessionTemplate session) { return session.getMapper(PullTaskGroupAccountMapper.class); }
        @Bean AccountMapper accountMapper(SqlSessionTemplate session) { return session.getMapper(AccountMapper.class); }
        @Bean PullTaskAccountActionMapper actions(SqlSessionTemplate session) { return session.getMapper(PullTaskAccountActionMapper.class); }
        @Bean PullTaskPullCallMapper calls(SqlSessionTemplate session) { return session.getMapper(PullTaskPullCallMapper.class); }
        @Bean PullTaskPullWaveMapper waves(SqlSessionTemplate session) { return session.getMapper(PullTaskPullWaveMapper.class); }
        @Bean PullTaskPullCallMemberAttemptMapper attempts(SqlSessionTemplate session) { return session.getMapper(PullTaskPullCallMemberAttemptMapper.class); }
        @Bean PullTaskMaterialMemberMapper materials(SqlSessionTemplate session) { return session.getMapper(PullTaskMaterialMemberMapper.class); }
        @Bean AccountProtocolLookupService accounts() { return mock(AccountProtocolLookupService.class); }
        @Bean AccountOnlineCommandService online() { return mock(AccountOnlineCommandService.class); }
        @Bean AccountCreatorDeletionService creatorDeletions() { return mock(AccountCreatorDeletionService.class); }
        @Bean GroupExecutionAccountSelector promoters() { return mock(GroupExecutionAccountSelector.class); }
        @Bean GroupDataPackageTaskProjectionService dataPackages() { return mock(GroupDataPackageTaskProjectionService.class); }
        @Bean PullTaskGroupRetryService retries() { return mock(PullTaskGroupRetryService.class); }
        @Bean PullTaskExecutionDispatchTrigger trigger() { return mock(PullTaskExecutionDispatchTrigger.class); }
        @Bean ProtocolCommandOutboxService outbox() { return mock(ProtocolCommandOutboxService.class); }
        @Bean PullTaskGroupProfileDispatcher profiles() { return mock(PullTaskGroupProfileDispatcher.class); }
        @Bean PullTaskExecutionDispatchProperties dispatchProperties() { return new PullTaskExecutionDispatchProperties(); }
        @Bean PullTaskOfflineRoleWaitProperties properties() { return new PullTaskOfflineRoleWaitProperties(); }
        @Bean PullTaskManagerAdminCandidateSelector selector() { return new PullTaskManagerAdminCandidateSelector(); }
        @Bean PullTaskStationSelectionService stations(PullTaskGroupAccountMapper roles, AccountProtocolLookupService accounts) {
            return new PullTaskStationSelectionService(roles, accounts);
        }

        @Bean PullTaskResourceRecoveryResources resources(PullTaskGroupExecutionMapper executions,
                AccountProtocolLookupService accounts, PullTaskStationSelectionService stations,
                GroupExecutionAccountSelector promoters, PullTaskAccountActionMapper actions,
                PullTaskManagerAdminCandidateSelector selector, PullTaskOfflineRoleWaitProperties properties,
                PullTaskPullerAccountStateService pullerStates, PullTaskCreatorOfflineGate creatorGate) {
            return new PullTaskResourceRecoveryResources(executions, accounts, stations, promoters, actions, selector,
                    properties, pullerStates, creatorGate);
        }

        @Bean PullTaskGroupExecutionFailureResources failureResources(PullTaskGroupExecutionMapper executions,
                PullTaskPullCallMapper calls, PullTaskPullCallMemberAttemptMapper attempts, PullTaskPullWaveMapper waves,
                PullTaskMaterialMemberMapper materials, PullTaskGroupAccountMapper roles) {
            return new PullTaskGroupExecutionFailureResources(executions, calls, attempts, waves,
                    new PullTaskGroupExecutionFailureParticipants(materials, roles));
        }

        @Bean PullTaskManagerPullerContactResources contactResources(PullTaskGroupExecutionMapper executions,
                AccountProtocolLookupService accounts, ProtocolCommandOutboxService outbox,
                PullTaskExecutionDispatchProperties properties, PullTaskOfflineRoleWaitProperties waitProperties) {
            return new PullTaskManagerPullerContactResources(executions, accounts, outbox, properties, waitProperties);
        }

        @Bean PullTaskPullerInviteResources inviteResources(PullTaskGroupExecutionMapper executions,
                AccountProtocolLookupService accounts, ProtocolCommandOutboxService outbox,
                PullTaskExecutionDispatchProperties properties, PullTaskManagerPullerContactTransactionService contacts) {
            return new PullTaskPullerInviteResources(executions, accounts, outbox, properties, contacts);
        }
    }
}
