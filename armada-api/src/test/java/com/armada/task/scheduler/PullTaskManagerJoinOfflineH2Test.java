package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.service.PullTaskManagerJoinResultService;
import com.armada.task.service.PullTaskManagerAdminResultService;
import com.armada.task.service.impl.PullTaskManagerJoinResultServiceImpl;
import com.armada.task.service.impl.PullTaskManagerAdminResultServiceImpl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 7.4：两个结果入口的明确离线拒绝不能消耗管理员，恢复后同角色、同动作可重试。 */
class PullTaskManagerJoinOfflineH2Test {
    private static final String OFFLINE = "ACCOUNT_NOT_ONLINE";
    private static final ProtocolAccountRef ACCOUNT = new ProtocolAccountRef(
            901L, ProtocolBackend.ANDROID, "manager-901", "15500000901");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private PullTaskGroupExecutionMapper executions;
    private PullTaskGroupAccountMapper roles;
    private PullTaskAccountActionMapper actions;
    private AccountProtocolLookupService lookup;
    private ProtocolCommandOutboxService outbox;
    private PullTaskParentCompletionService completion;
    private PullTaskManagerJoinTransactionService transactions;
    private PullTaskManagerJoinResultService callbacks;
    private PullTaskManagerAdminResultService adminCallbacks;
    private PullTaskOfflineRoleWaitProperties offlineProperties;

    @BeforeEach
    void setUp() throws Exception {
        var source = PullTaskNormalLinkH2Support.dataSource("managerOffline" + System.nanoTime());
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(source);
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        var config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                "mapper/task/PullTaskGroupExecutionMapper.xml", "mapper/task/PullTaskGroupAccountMapper.xml",
                "mapper/task/PullTaskAccountActionMapper.xml");
        var sessions = new SqlSessionTemplate(factory);
        executions = sessions.getMapper(PullTaskGroupExecutionMapper.class);
        roles = sessions.getMapper(PullTaskGroupAccountMapper.class);
        actions = sessions.getMapper(PullTaskAccountActionMapper.class);
        lookup = mock(AccountProtocolLookupService.class);
        outbox = mock(ProtocolCommandOutboxService.class);
        completion = mock(PullTaskParentCompletionService.class);
        offlineProperties = new PullTaskOfflineRoleWaitProperties();
        var dispatch = new PullTaskExecutionDispatchProperties();
        dispatch.setRetryDelayMs(5_000L);
        transactions = transactional(new PullTaskManagerJoinTransactionService(
                sessions.getMapper(PullTaskMapper.class), sessions.getMapper(PullTaskStandardSettingMapper.class),
                roles, actions, new PullTaskManagerJoinResources(executions, lookup, completion, outbox,
                        dispatch, offlineProperties)), PullTaskManagerJoinTransactionService.class);
        callbacks = transactional(new PullTaskManagerJoinResultServiceImpl(actions, roles, executions, completion,
                dispatch, new PullTaskOperationDelayPolicy(() -> 3_000L), mock(GroupInviteLinkService.class),
                offlineProperties), PullTaskManagerJoinResultService.class);
        adminCallbacks = transactional(new PullTaskManagerAdminResultServiceImpl(actions, roles, executions,
                dispatch, new PullTaskOperationDelayPolicy(() -> 3_000L), offlineProperties),
                PullTaskManagerAdminResultService.class);
        seed();
        TenantContext.set(7L);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @ParameterizedTest
    @CsvSource({"false,0", "false,3", "true,0", "true,3"})
    void offlineRejectionWaitsWithoutFailureAndOriginalManagerCanRetry(boolean synchronous, int previousAttempt) {
        jdbc.update("UPDATE pull_task_account_action SET attempt_no=?,retryable=FALSE WHERE id=601", previousAttempt);
        rejectOffline(synchronous);
        assertThat(action().getActionStatus()).isEqualTo(PullTaskActionStatus.PENDING.code());
        assertThat(action().getAttemptNo()).isEqualTo(previousAttempt);
        assertThat(action().getRetryable()).isTrue();
        var manager = roles.selectById(501L);
        assertThat(manager.getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(manager.getMembershipFailureCount()).isEqualTo(2L);
        assertThat(manager.getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        var waiting = executions.selectById(11L);
        assertThat(waiting.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(waiting.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(waiting.getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.MANAGER.code());
        assertThat(waiting.getFinishedAt()).isNull();
        verifyNoInteractions(completion, outbox);

        // 复用 managerCheck 已有的上线资格恢复 SQL，模拟等待行再次取得调度租约。
        roles.restoreValidatedAvailability(List.of(901L), PullTaskGroupAccountRole.MANAGER.code(),
                List.of(PullTaskGroupAccountAvailability.OFFLINE.code()),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), 7_000L);
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2,lock_owner='retry',lock_expires_at=20000 WHERE id=11");
        when(lookup.findEligibleManagerProtocolRefs(List.of(901L))).thenReturn(List.of(ACCOUNT));
        when(lookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(ACCOUNT));
        when(outbox.enqueuePullTaskGroupJoinCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task-100", List.of("new-command"), 1));
        transactions.prepare(executions.selectById(11L), "retry", 7_001L);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_group_account", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_account_action", Integer.class)).isOne();
        assertThat(action().getCommandId()).isEqualTo("new-command");
        assertThat(action().getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(action().getAttemptNo()).isEqualTo(Math.max(1, previousAttempt) + 1);
        assertThat(action().getReasonCode()).isNull();
        assertThat(action().getReasonMessage()).isNull();
        assertThat(action().getResultAt()).isNull();
        assertThat(action().getRetryable()).isNull();
        assertThat(roles.selectById(501L).getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.JOINING.code());
        assertThat(callbacks.apply(callback())).isFalse();
        assertThat(action().getCommandId()).isEqualTo("new-command");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledFlagPreservesLegacyFailureInBothResultEntrypoints(boolean synchronous) {
        offlineProperties.setEnabled(false);
        rejectOffline(synchronous);
        assertThat(action().getActionStatus()).isEqualTo(PullTaskActionStatus.FAILED.code());
        assertThat(roles.selectById(501L).getMembershipStatus()).isEqualTo(PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
    }

    @Test
    void disabledFlagReusesLegacySubmissionEvenForAPendingOfflineActionLeftBeforeRollback() {
        offlineProperties.setEnabled(false);
        jdbc.update("""
                UPDATE pull_task_account_action SET action_status=1,attempt_no=0,reason_code=?,
                  reason_message='prior offline',result_at=123,retryable=TRUE WHERE id=601
                """, OFFLINE);
        jdbc.update("UPDATE pull_task_group_account SET membership_status=0 WHERE id=501");
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner='retry',lock_expires_at=20000 WHERE id=11");
        when(lookup.findEligibleManagerProtocolRefs(List.of(901L))).thenReturn(List.of(ACCOUNT));
        when(lookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(ACCOUNT));
        when(outbox.enqueuePullTaskGroupJoinCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task-100", List.of("legacy-new-command"), 1));

        transactions.prepare(executions.selectById(11L), "retry", 7_001L);

        assertThat(action().getCommandId()).isEqualTo("legacy-new-command");
        assertThat(action().getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(action().getAttemptNo()).isZero();
        assertThat(action().getReasonCode()).isEqualTo(OFFLINE);
        assertThat(action().getReasonMessage()).isEqualTo("prior offline");
        assertThat(action().getResultAt()).isEqualTo(123L);
        assertThat(action().getRetryable()).isTrue();
    }

    @Test
    void lateOfflineJoinCallbackCannotMoveAnExecutionPastTheJoinStageBackToWaiting() {
        jdbc.update("UPDATE pull_task_group_execution SET stage=? WHERE id=11", PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(callbacks.apply(callback())).isFalse();
        assertThat(action().getActionStatus()).isEqualTo(PullTaskActionStatus.SUBMITTED.code());
        assertThat(executions.selectById(11L).getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    @ParameterizedTest
    @CsvSource({"5,true", "10,true", "5,false", "10,false"})
    void sharedPullerJoinCallbackWaitsInItsOwnStageWithoutJoinFailure(int stage, boolean enabled) {
        offlineProperties.setEnabled(enabled);
        jdbc.update("UPDATE pull_task_group_execution SET stage=? WHERE id=11", stage);
        jdbc.update("UPDATE pull_task_group_account SET role_type=? WHERE id=501", PullTaskGroupAccountRole.PULLER.code());
        assertThat(callbacks.apply(callback())).isTrue();
        assertThat(action().getActionStatus()).isEqualTo(enabled
                ? PullTaskActionStatus.PENDING.code() : PullTaskActionStatus.FAILED.code());
        assertThat(roles.selectById(501L).getMembershipStatus()).isEqualTo(enabled
                ? PullTaskGroupAccountMembershipStatus.NOT_JOINED.code()
                : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
        assertThat(roles.selectById(501L).getMembershipFailureCount()).isEqualTo(2L);
        assertThat(executions.selectById(11L).getStage()).isEqualTo(stage);
        if (enabled) {
            assertThat(executions.selectById(11L).getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
            assertThat(executions.selectById(11L).getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.PULLER.code());
            assertThat(roles.selectById(501L).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adminPromotionOfflineKeepsOriginalActorRetryableWithoutFailure(boolean enabled) {
        offlineProperties.setEnabled(enabled);
        jdbc.update("UPDATE pull_task_group_execution SET stage=?,group_jid='group@g.us' WHERE id=11",
                PullTaskExecutionStage.MANAGER_ADMIN.code());
        jdbc.update("UPDATE pull_task_group_account SET membership_status=2,admin_status=1 WHERE id=501");
        jdbc.update("""
                INSERT INTO pull_task_group_account(id,tenant_id,task_id,group_execution_id,account_id,account_phone,
                  role_type,role_seq,created_at,updated_at) VALUES(502,7,100,11,902,'15500000902',?,1,100,100)
                """, PullTaskGroupAccountRole.PROMOTER.code());
        jdbc.update("UPDATE pull_task_account_action SET action_type=?,actor_group_account_id=502 WHERE id=601",
                PullTaskAccountActionType.PROMOTE_MANAGER.code());
        assertThat(adminCallbacks.apply(new PullTaskManagerAdminCallback(7,100,11,601,902,"actor-902",
                "old-command",3,"15500000901@s.whatsapp.net", PullTaskManagerAdminProtocolOutcome.FAILED,
                OFFLINE,"offline",false,2_000L))).isTrue();
        var action = actions.selectByCommandId("old-command");
        assertThat(action.getActionStatus()).isEqualTo(enabled
                ? PullTaskActionStatus.PENDING.code() : PullTaskActionStatus.FAILED.code());
        assertThat(action.getRetryable()).isEqualTo(enabled);
        assertThat(action.getAttemptNo()).isEqualTo(3);
        assertThat(roles.selectById(501L).getAdminStatus()).isEqualTo(PullTaskGroupAccountAdminStatus.PENDING.code());
        if (enabled) {
            assertThat(executions.selectById(11L).getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
            assertThat(executions.selectById(11L).getReasonCode()).isEqualTo("MANAGER_ADMIN_ACTOR_UNAVAILABLE");
            var selected = new PullTaskManagerAdminCandidateSelector().select(List.of(
                    new GroupExecutionAccount(903L,"ANDROID","new-actor","15500000903",true),
                    new GroupExecutionAccount(902L,"ANDROID","actor-902","15500000902",true)),
                    List.of(roles.selectById(502L)), List.of(action), 501L).orElseThrow();
            assertThat(selected.candidate().accountId()).isEqualTo(902L);
            assertThat(selected.action().getId()).isEqualTo(601L);
        }
    }

    private com.armada.task.model.entity.PullTaskAccountAction action() {
        return actions.selectByExecutionAndType(11L, PullTaskAccountActionType.JOIN_BY_LINK.code()).get(0);
    }

    private void rejectOffline(boolean synchronous) {
        if (synchronous) {
            jdbc.update("UPDATE pull_task_group_execution SET lock_owner='worker',lock_expires_at=20000 WHERE id=11");
            var work = new PullTaskManagerJoinWork(7L, 11L, 501L, 601L,
                    new PullTaskManagerJoinPayload(ACCOUNT, "InviteCode", "operation-601", "worker", 1));
            assertThat(transactions.complete(work, PullTaskManagerJoinOutcome.managerFailed(OFFLINE), 2_000L))
                    .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        } else {
            assertThat(callbacks.apply(callback())).isTrue();
        }
    }

    private PullTaskManagerJoinCallback callback() {
        return new PullTaskManagerJoinCallback(7L, 100L, 11L, 601L, "old-command",
                PullTaskManagerJoinProtocolOutcome.FAILED, null, OFFLINE, "offline", false, 2_000L);
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO pull_task(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at)
                VALUES(100,7,'manager retry','NORMAL_LINK','EXECUTING','{}',100,100)
                """);
        jdbc.update("""
                INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,
                  execution_status,stage,version,invite_code,normalized_link,created_at,updated_at)
                VALUES(11,7,100,1,1,'test',2,?,1,'InviteCode','https://chat.whatsapp.com/InviteCode',100,100)
                """, PullTaskExecutionStage.MANAGER_JOIN.code());
        jdbc.update("""
                INSERT INTO pull_task_group_account(id,tenant_id,task_id,group_execution_id,account_id,account_phone,
                  role_type,role_seq,membership_status,membership_failure_count,created_at,updated_at)
                VALUES(501,7,100,11,901,'15500000901',?,1,1,2,100,100)
                """, PullTaskGroupAccountRole.MANAGER.code());
        jdbc.update("""
                INSERT INTO pull_task_account_action(id,tenant_id,task_id,group_execution_id,action_type,
                  actor_group_account_id,target_group_account_id,action_status,command_id,attempt_no,created_at,updated_at)
                VALUES(601,7,100,11,?,501,501,2,'old-command',3,100,100)
                """, PullTaskAccountActionType.JOIN_BY_LINK.code());
    }

    private <T> T transactional(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        if (!type.isInterface()) proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
