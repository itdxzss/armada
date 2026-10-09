package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.AccountRoleAvailability;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.impl.AccountProtocolLookupServiceImpl;
import com.armada.account.takeover.AccountAutoTakeoverProperties;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 7.3 管理员宽限、到期替换和原动作重发，使用真实 Mapper、租户插件及 Spring 事务。 */
@SpringJUnitConfig(PullTaskManagerOfflineGraceH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class PullTaskManagerOfflineGraceH2Test {
    private static final long NOW = 100_000L;
    private static final String OWNER = "manager-grace-worker";

    @Autowired private DataSource source;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private PullTaskGroupAccountMapper roles;
    @Autowired private PullTaskAccountActionMapper actions;
    @Autowired private AccountMapper accountMapper;
    @Autowired private AccountProtocolLookupService accounts;
    @Autowired private ProtocolCommandOutboxService outbox;
    @Autowired private PullTaskOfflineRoleWaitProperties properties;
    @Autowired private PullTaskResourceRecoveryTransactionService recovery;
    @Autowired private PullTaskManagerJoinTransactionService joins;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accounts, outbox);
        properties.setEnabled(true);
        properties.setReplaceableGraceMs(30_000L);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(source);
        jdbc.update("INSERT INTO pull_task (id,tenant_id,task_type,task_name,mode,status,config_json,created_at,updated_at)"
                + " VALUES (100,7,'STANDARD','task','NORMAL_LINK','EXECUTING','{}',100,100)");
        jdbc.update("INSERT INTO pull_task_standard_setting (tenant_id,task_id,auto_start,material_admin_timing,"
                + "pull_count_min,pull_count_max,pull_interval_seconds,puller_count_per_group,station_count_per_call,"
                + "concurrent_group_count,puller_risk_minutes,required_manager_count,manager_group_id,puller_group_id,"
                + "station_group_id,manager_group_name,puller_group_name,station_group_name,created_at,updated_at)"
                + " VALUES (7,100,1,1,1,2,1,2,1,1,5,1,88,89,90,'managers','pullers','stations',100,100)");
        jdbc.update("INSERT INTO pull_task_group_execution (id,tenant_id,task_id,seq,source_file_index,source_file_name,"
                + "execution_status,stage,wait_resource_type,group_jid,invite_code,reason_code,next_run_at,version,"
                + "lock_owner,lock_expires_at,created_at,updated_at) VALUES (200,7,100,1,1,'members.txt',3,?,?,"
                + "'group@g.us','AAAA','ACCOUNT_NOT_ONLINE',0,6,?,?,100,100)",
                PullTaskExecutionStage.MANAGER_JOIN.code(), PullTaskWaitResourceType.MANAGER.code(), OWNER, NOW + 5_000L);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of());
        jdbc.update("INSERT INTO account (id,tenant_id,ws_phone,protocol_account_id) VALUES (905,7,'phone-905','protocol-905')");
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void configuredGracePreservesOriginalManagerAndFixedDeadlineAcrossProbes() {
        properties.setReplaceableGraceMs(45_000L);
        PullTaskGroupAccount original = manager(901L, 1);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 80_000L)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 90_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertWaiting(125_000L);
        assertThat(managers()).singleElement().extracting(PullTaskGroupAccount::getId).isEqualTo(original.getId());
        verify(accounts, never()).findOnlineEligibleManagersByGroupId(88L);
        lease(101_000L);
        assertThat(recovery.recover(candidate(), OWNER, 101_000L, 90_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertWaiting(125_000L);
    }

    @Test
    void anyWaitingManagerPreventsReplacementAndEarliestDeadlineWins() {
        manager(901L, 1);
        manager(902L, 2);
        manager(903L, 3);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(
                901L, recovering(901L, 85_000L), 902L, recovering(902L, 75_000L),
                903L, new AccountRoleAvailability(903L, AccountRoleAvailability.Kind.TERMINAL, NOW, 2, null)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertWaiting(105_000L);
        assertThat(managers()).hasSize(3);
        verify(accounts, never()).findOnlineEligibleManagersByGroupId(88L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline", "terminal", "missing"})
    void expiredOrUnrecoverableManagerUsesExistingReplacementAndPreservesHistory(String reason) {
        PullTaskGroupAccount original = manager(901L, 1);
        AccountRoleAvailability availability = "deadline".equals(reason) ? recovering(901L, 70_000L)
                : new AccountRoleAvailability(901L, AccountRoleAvailability.Kind.TERMINAL, NOW, 2, null);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn("missing".equals(reason)
                ? Map.of() : Map.of(901L, availability));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(901L), protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplacement(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed", "admin-failed"})
    void removedOrAdminFailedHistoryDoesNotHoldAnotherGracePeriod(String excluded) {
        PullTaskGroupAccount original = manager(901L, 1);
        if ("removed".equals(excluded)) {
            roles.markUnavailable(original.getId(), PullTaskGroupAccountAvailability.REMOVED.code(), "MANAGER_UNAVAILABLE", null, NOW - 1L);
        } else {
            jdbc.update("UPDATE pull_task_group_account SET admin_status=? WHERE id=?", PullTaskGroupAccountAdminStatus.FAILED.code(), original.getId());
        }
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, NOW - 1L)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verify(accounts, never()).findRoleAvailability(anyCollection());
        assertReplacement(original);
    }

    @Test
    void exhaustedGroupChangesToResourceShortageOnlyAfterOriginalGraceExpires() {
        PullTaskGroupAccount original = manager(901L, 1);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 80_000L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertWaiting(110_000L);
        lease(110_000L);
        assertThat(recovery.recover(candidate(), OWNER, 110_000L, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(candidate().getReasonCode()).isEqualTo("MANAGER_UNAVAILABLE");
        assertThat(candidate().getNextRunAt()).isEqualTo(140_000L);
        assertThat(managers()).singleElement().extracting(PullTaskGroupAccount::getId).isEqualTo(original.getId());
        assertThat(roles.selectById(original.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
    }

    @Test
    void recoveredOriginalManagerResumesAndResubmitsTheSamePendingJoinAction() {
        PullTaskGroupAccount original = manager(901L, 1);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=?,membership_failure_count=2 WHERE id=?",
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(), original.getId());
        jdbc.update("INSERT INTO pull_task_account_action (id,tenant_id,task_id,group_execution_id,action_type,"
                + "actor_group_account_id,target_group_account_id,action_status,command_id,attempt_no,reason_code,"
                + "reason_message,retryable,result_at,created_at,updated_at) VALUES (601,7,100,200,?,?,?,?,'offline-command',"
                + "2,'ACCOUNT_NOT_ONLINE','offline rejection',TRUE,90000,100,90000)",
                PullTaskAccountActionType.JOIN_BY_LINK.code(), original.getId(), original.getId(), PullTaskActionStatus.PENDING.code());
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, 80_000L)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));
        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        when(accounts.findEligibleManagerProtocolRefs(List.of(901L))).thenReturn(List.of(protocol(901L)));
        when(accounts.findActiveProtocolRef(901L)).thenReturn(Optional.of(protocol(901L)));
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L,
                new AccountRoleAvailability(901L, AccountRoleAvailability.Kind.ONLINE, null, 1, null)));
        lease(NOW + 1L);
        assertThat(recovery.recover(candidate(), OWNER, NOW + 1L, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(candidate().getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(roles.selectById(original.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        when(outbox.enqueuePullTaskGroupJoinCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task:100", List.of("retried-command"), 1));
        lease(NOW + 2L);

        joins.prepare(candidate(), OWNER, NOW + 2L);

        assertThat(managers()).singleElement().extracting(PullTaskGroupAccount::getId).isEqualTo(original.getId());
        assertThat(actions.selectByExecutionAndType(200L, PullTaskAccountActionType.JOIN_BY_LINK.code()))
                .singleElement().satisfies(action -> {
                    assertThat(action.getId()).isEqualTo(601L);
                    assertThat(action.getCommandId()).isEqualTo("retried-command");
                    assertThat(action.getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
                    assertThat(action.getAttemptNo()).isEqualTo(3);
                    assertThat(action.getReasonCode()).isNull();
                });
        assertThat(roles.selectById(original.getId()).getMembershipFailureCount()).isEqualTo(2L);
        assertThat(roles.selectById(original.getId()).getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());
        verify(accounts, never()).findOnlineEligibleManagersByGroupId(88L);
    }

    @Test
    void disabledTaskFlagReplacesImmediatelyWithoutAvailabilityLookup() {
        properties.setEnabled(false);
        PullTaskGroupAccount original = manager(901L, 1);
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L, recovering(901L, NOW - 1L)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplacement(original);
        verify(accounts, never()).findRoleAvailability(anyCollection());
    }

    @Test
    void onlineManagerWithFinalJoinFailureKeepsExistingReplacementPath() {
        PullTaskGroupAccount original = manager(901L, 1);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=? WHERE id=?",
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(), original.getId());
        when(accounts.findEligibleManagerProtocolRefs(List.of(901L))).thenReturn(List.of(protocol(901L)));
        when(accounts.findRoleAvailability(anyCollection())).thenReturn(Map.of(901L,
                new AccountRoleAvailability(901L, AccountRoleAvailability.Kind.ONLINE, null, 1, null)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenReturn(List.of(protocol(905L)));

        assertThat(recovery.recover(candidate(), OWNER, NOW, 30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        assertReplacement(original);
        assertThat(roles.selectById(original.getId()).getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
    }

    @ParameterizedTest
    @CsvSource({"desired-offline,true", "tripped-breaker,false"})
    void onlineTerminalOriginalManagerCannotBeRestoredByLegacyEligibility(String terminalFact, boolean hasReplacement) {
        PullTaskGroupAccount original = manager(901L, 1);
        jdbc.execute("ALTER TABLE account ADD COLUMN account_group_id BIGINT DEFAULT 88");
        jdbc.execute("ALTER TABLE account ADD COLUMN protocol_id VARCHAR(32) DEFAULT 'WEB'");
        jdbc.execute("ALTER TABLE account ADD COLUMN deleted_at BIGINT");
        jdbc.execute("CREATE TABLE account_state (account_id BIGINT PRIMARY KEY,tenant_id BIGINT,account_state INT,"
                + "login_state INT,desired_login_state INT,mute_status INT,offline_since BIGINT,risk_status INT)");
        jdbc.execute("CREATE TABLE account_takeover_breaker (account_id BIGINT PRIMARY KEY,tenant_id BIGINT,tripped_at BIGINT)");
        jdbc.update("INSERT INTO account_state VALUES (901,7,2,1,?,NULL,NULL,NULL)",
                "desired-offline".equals(terminalFact) ? 2 : 1);
        if ("tripped-breaker".equals(terminalFact)) {
            jdbc.update("INSERT INTO account_takeover_breaker VALUES (901,7,99000)");
        }
        if (hasReplacement) {
            jdbc.update("INSERT INTO account_state VALUES (905,7,2,1,1,NULL,NULL,NULL)");
        }
        AccountProtocolLookupService realAccounts = new AccountProtocolLookupServiceImpl(
                accountMapper, new AccountAutoTakeoverProperties());
        assertThat(realAccounts.findEligibleManagerProtocolRefs(List.of(901L)))
                .extracting(ProtocolAccountRef::armadaAccountId).containsExactly(901L);
        assertThat(realAccounts.findRoleAvailability(List.of(901L)).get(901L).kind())
                .isEqualTo(AccountRoleAvailability.Kind.TERMINAL);
        when(accounts.findEligibleManagerProtocolRefs(anyList())).thenAnswer(invocation ->
                realAccounts.findEligibleManagerProtocolRefs(invocation.getArgument(0)));
        when(accounts.findOnlineEligibleManagersByGroupId(88L)).thenAnswer(invocation ->
                realAccounts.findOnlineEligibleManagersByGroupId(88L));
        when(accounts.findRoleAvailability(anyCollection())).thenAnswer(invocation ->
                realAccounts.findRoleAvailability(invocation.getArgument(0)));

        PullTaskExecutionDispatchResult result = recovery.recover(candidate(), OWNER, NOW, 30_000L);

        assertThat(roles.selectById(original.getId()).getAvailabilityStatus())
                .isNotEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        if (hasReplacement) {
            assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
            assertReplacement(original);
        } else {
            assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
            assertThat(candidate().getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
            assertThat(candidate().getReasonCode()).isEqualTo("MANAGER_UNAVAILABLE");
            assertThat(managers()).hasSize(1);
        }
    }

    private void assertWaiting(long deadline) {
        PullTaskGroupExecution saved = candidate();
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getReasonCode()).isEqualTo("MANAGER_RECONNECTING");
        assertThat(saved.getNextRunAt()).isEqualTo(deadline);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(saved.getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.MANAGER.code());
    }

    private void assertReplacement(PullTaskGroupAccount original) {
        assertThat(managers()).hasSize(2);
        assertThat(roles.selectById(original.getId()).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.REMOVED.code());
        PullTaskGroupAccount replacement = managers().get(1);
        assertThat(replacement.getAccountId()).isEqualTo(905L);
        assertThat(replacement.getRoleSeq()).isEqualTo(2);
        assertThat(replacement.getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(candidate().getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(candidate().getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    private PullTaskGroupAccount manager(long accountId, int seq) {
        jdbc.update("INSERT INTO account (id,tenant_id,ws_phone,protocol_account_id) VALUES (?,7,?,?)",
                accountId, "phone-" + accountId, "protocol-" + accountId);
        PullTaskGroupAccount role = new PullTaskGroupAccount();
        role.setTaskId(100L);
        role.setGroupExecutionId(200L);
        role.setAccountId(accountId);
        role.setAccountPhone("phone-" + accountId);
        role.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        role.setRoleSeq(seq);
        role.setSourceType(1);
        role.setSelectionMode(1);
        role.setEntryMode(1);
        role.setCreatedAt(100L);
        role.setUpdatedAt(100L);
        roles.insert(role);
        roles.markUnavailable(role.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(), "ACCOUNT_NOT_ONLINE", null, 90_000L);
        return roles.selectById(role.getId());
    }

    private List<PullTaskGroupAccount> managers() {
        return roles.selectByExecutionAndRole(200L, PullTaskGroupAccountRole.MANAGER.code());
    }

    private PullTaskGroupExecution candidate() { return executions.selectById(200L); }

    private void lease(long now) {
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner=?,lock_expires_at=? WHERE id=200", OWNER, now + 5_000L);
    }

    private static AccountRoleAvailability recovering(long id, long since) {
        return new AccountRoleAvailability(id, AccountRoleAvailability.Kind.RECOVERING, since, 2, null);
    }

    private static ProtocolAccountRef protocol(long id) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB, "protocol-" + id, "phone-" + id);
    }

    /** 复用阶段 3 的真实 Mapper/事务装配，但使用独立数据库并加入原管理员入群服务。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(PullTaskManagerJoinTransactionService.class)
    static class TestConfig extends PullTaskOfflineResourceRecoveryH2Test.TestConfig {
        @Bean @Override DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("manager_offline_grace");
        }

        @Bean PullTaskManagerJoinResources managerJoinResources(PullTaskGroupExecutionMapper executions,
                AccountProtocolLookupService accounts, PullTaskParentCompletionService completion,
                ProtocolCommandOutboxService outbox, PullTaskExecutionDispatchProperties dispatch,
                PullTaskOfflineRoleWaitProperties offline) {
            return new PullTaskManagerJoinResources(executions, accounts, completion, outbox, dispatch, offline);
        }
    }
}
