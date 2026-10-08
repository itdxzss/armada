package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.armada.account.model.AccountCreatorReservation;
import com.armada.account.model.AccountRoleAvailability;
import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.impl.AccountCreatorDeletionServiceImpl;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureParticipants;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureResources;
import com.armada.task.service.impl.PullTaskGroupExecutionFailureServiceImpl;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 建群入口和预留账号恢复的真实任务 Mapper H2 回归；服务边界替身不代替持久化。 */
class PullTaskCreatorOfflineGateH2Test {
    private static final long NOW = 500_000L;
    private static final long RETRY = 5_000L;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private PullTaskGroupExecutionMapper executions;
    private AccountProtocolLookupService lookup;
    private AccountOnlineCommandService online;
    private PullTaskGroupProfileDispatcher profiles;
    private PullTaskOfflineRoleWaitProperties properties;
    private PullTaskCreatorOfflineGate gate;
    private PullTaskGroupCreateTransactionService creates;
    private PullTaskGroupExecutionFailureService failures;
    private PullTaskParentCompletionService completion;

    @BeforeEach void setUp() throws Exception {
        TenantContext.set(7L);
        DataSource source = PullTaskNormalLinkH2Support.dataSource("creator_wait_" + UUID.randomUUID());
        PullTaskNormalLinkH2Support.resetSchema(source);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                new MyBatisConfig().mybatisPlusInterceptor(new MyBatisConfig().tenantLineHandler()),
                "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskGroupExecutionMapper.xml",
                "mapper/task/PullTaskGroupAccountMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                "mapper/task/PullTaskStandardGroupSettingMapper.xml", "mapper/task/PullTaskAccountActionMapper.xml",
                "mapper/task/PullTaskPullCallMapper.xml", "mapper/task/PullTaskPullWaveMapper.xml",
                "mapper/task/PullTaskMaterialMemberMapper.xml", "mapper/account/AccountCreatorDeletionMapper.xml");
        var sql = new SqlSessionTemplate(factory);
        executions = sql.getMapper(PullTaskGroupExecutionMapper.class);
        var roles = sql.getMapper(PullTaskGroupAccountMapper.class);
        properties = new PullTaskOfflineRoleWaitProperties();
        lookup = mock(AccountProtocolLookupService.class);
        online = mock(AccountOnlineCommandService.class);
        profiles = mock(PullTaskGroupProfileDispatcher.class);
        completion = mock(PullTaskParentCompletionService.class);
        var deletions = transactional(new AccountCreatorDeletionServiceImpl(
                sql.getMapper(AccountCreatorDeletionMapper.class)), AccountCreatorDeletionService.class);
        failures = transactional(new PullTaskGroupExecutionFailureServiceImpl(
                new PullTaskGroupExecutionFailureResources(executions,
                        sql.getMapper(PullTaskPullCallMapper.class),
                        sql.getMapper(PullTaskPullCallMemberAttemptMapper.class),
                        sql.getMapper(PullTaskPullWaveMapper.class),
                        new PullTaskGroupExecutionFailureParticipants(
                                sql.getMapper(PullTaskMaterialMemberMapper.class), roles)),
                completion, deletions, properties), PullTaskGroupExecutionFailureService.class);
        gate = new PullTaskCreatorOfflineGate(roles, lookup, online, properties, manager, failures);
        creates = transactional(new PullTaskGroupCreateTransactionService(
                new PullTaskGroupCreatePersistence(sql.getMapper(PullTaskMapper.class),
                        sql.getMapper(PullTaskStandardSettingMapper.class),
                        sql.getMapper(PullTaskStandardGroupSettingMapper.class), executions, roles,
                        sql.getMapper(PullTaskAccountActionMapper.class)),
                new PullTaskGroupCreateResources(lookup, mock(GroupCreatePort.class), mock(GroupInvitePort.class),
                        mock(GroupLinkRegistryService.class), profiles, mock(FixedAccountGroupMetadataPort.class)),
                mock(PullTaskCreatorDeletionTransactionService.class), gate), PullTaskGroupCreateTransactionService.class);
        jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,creation_mode,status,config_json,created_at,updated_at) "
                + "VALUES(1,7,'task','NORMAL_LINK','NEW_GROUP','EXECUTING','{}',100,100)");
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,stage,create_step,"
                + "manual_paused,next_run_at,lock_owner,lock_expires_at,version,create_operation_id,group_subject,group_jid,created_at,updated_at) "
                + "VALUES(11,7,1,1,1,'members.txt',2,9,2,0,0,'worker',?,2,'create-11','subject','group@g.us',100,100)", NOW + 100_000);
        jdbc.update("INSERT INTO pull_task_group_account(id,tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,role_seq,created_at,updated_at) "
                + "VALUES(101,7,1,11,901,'8613800000901',4,1,100,100),(102,7,1,11,902,'8613800000902',1,1,100,100)");
        jdbc.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id) VALUES(901,7,'8613800000901','acc-901')");
        jdbc.update("INSERT INTO pull_task_standard_group_setting(tenant_id,task_id,is_group_setting_enabled,setting_timing,group_name,"
                + "group_description,is_material_filename_as_group_name,edit_permission_mode,mute_mode,link_permission_mode,"
                + "disappearing_message_mode,created_at,updated_at) VALUES(7,1,1,1,'subject','description',0,0,0,2,0,100,100)");
        when(lookup.findActiveProtocolRef(901L)).thenReturn(Optional.of(ref()));
        when(lookup.findOnlineProtocolRefs(List.of(901L))).thenReturn(List.of());
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, null, 2);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void recoveringCreatorDefersWithoutPreparingCreateCommand() {
        var result = creates.prepareCreate(candidate(), NOW, RETRY);
        assertThat(result.ready()).isFalse();
        assertThat(result.completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertWaiting(NOW + RETRY);
    }

    @Test void configuredDeadlineCapsNextRunAt() {
        properties.setCreatorGraceMs(2_000L);
        creates.prepareCreate(candidate(), NOW, RETRY);
        assertWaiting(NOW + 1_000L);
    }

    @Test void timeoutFailsExecutionAndReleasesOnlyReservedCreator() {
        reserve("RESERVED");
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 180_000, reservation(7, 1, 11), 2);
        assertThat(creates.prepareCreate(candidate(), NOW, RETRY).completedResult())
                .isEqualTo(PullTaskExecutionDispatchResult.FAILED);
        assertFailed();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion", Integer.class)).isZero();
    }

    @Test void terminalCreatorImmediatelyFailsAndPreservesDeletingRecord() {
        reserve("DELETING");
        availability(AccountRoleAvailability.Kind.TERMINAL, NOW - 1_000, reservation(7, 1, 11), 2);
        assertThat(creates.prepareCreate(candidate(), NOW, RETRY).completedResult())
                .isEqualTo(PullTaskExecutionDispatchResult.FAILED);
        assertFailed();
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM account_creator_deletion", String.class)).isEqualTo("DELETING");
    }

    @Test void reservedCreatorRecoveryRunsOnceAfterCommitInNewTransactionWithTenantRestored() {
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, reservation(7, 1, 11), 2);
        doAnswer(call -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            assertWaiting(NOW + RETRY);
            jdbc.update("UPDATE pull_task SET remark='recovered' WHERE id=1");
            return null;
        }).when(online).reonlineReservedCreator(901L, 1L, 11L);
        TenantContext.set(19L);
        tx.executeWithoutResult(status -> {
            creates.prepareCreate(withTenantCandidate(), NOW, RETRY);
            verify(online, never()).reonlineReservedCreator(anyLong(), anyLong(), anyLong());
        });
        verify(online).reonlineReservedCreator(901L, 1L, 11L);
        assertThat(TenantContext.get()).isEqualTo(19L);
        assertThat(jdbc.queryForObject("SELECT remark FROM pull_task WHERE id=1", String.class)).isEqualTo("recovered");
    }

    @Test void recoveryFailureDoesNotRollbackCommittedWait() {
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, reservation(7, 1, 11), 2);
        doThrow(new IllegalStateException("proxy unavailable")).when(online)
                .reonlineReservedCreator(901L, 1L, 11L);
        creates.prepareCreate(candidate(), NOW, RETRY);
        assertWaiting(NOW + RETRY);
    }

    @Test void rollbackNeverRequestsRecovery() {
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, reservation(7, 1, 11), 2);
        tx.executeWithoutResult(status -> {
            creates.prepareCreate(candidate(), NOW, RETRY);
            status.setRollbackOnly();
        });
        verify(online, never()).reonlineReservedCreator(anyLong(), anyLong(), anyLong());
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution", String.class)).isNull();
    }

    @Test void lostLeaseNeverRequestsRecovery() {
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, reservation(7, 1, 11), 2);
        var stale = candidate();
        jdbc.update("UPDATE pull_task_group_execution SET version=version+1 WHERE id=11");
        assertThat(creates.prepareCreate(stale, NOW, RETRY).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.LOST);
        verify(online, never()).reonlineReservedCreator(anyLong(), anyLong(), anyLong());
    }

    @Test void reservationMustMatchTenantTaskAndExecutionAndBeOffline() {
        for (var r : List.of(reservation(8, 1, 11), reservation(7, 2, 11), reservation(7, 1, 12))) {
            availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, r, 2);
            assertThat(gate.evaluate(candidate(), NOW).requestReservedReonline()).isFalse();
        }
        availability(AccountRoleAvailability.Kind.RECOVERING, NOW - 1_000, reservation(7, 1, 11), 3);
        assertThat(gate.evaluate(candidate(), NOW).requestReservedReonline()).isFalse();
    }

    @Test void onlineCreatorPreparesCreateCommandImmediately() {
        availability(AccountRoleAvailability.Kind.ONLINE, null, null, 1);
        assertThat(creates.prepareCreate(candidate(), NOW, RETRY).ready()).isTrue();
    }

    @Test void profileEntryWaitsWithoutDispatchingCommands() {
        jdbc.update("UPDATE pull_task_group_execution SET create_step=4 WHERE id=11");
        assertThat(creates.prepareProfile(candidate(), RETRY, NOW).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(profiles, never()).dispatchIfDue(any(), any(), anyLong());
        assertWaiting(NOW + RETRY);
    }

    @Test void inviteEntryWaitsWithoutPreparingInviteCommand() {
        reclaim(5);
        assertThat(creates.prepareInvite(candidate(), RETRY, NOW).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertWaiting(NOW + RETRY);
    }

    @Test void profileRepairWaitsWithoutWritingReplacementCommand() {
        jdbc.update("UPDATE pull_task_group_execution SET create_step=4 WHERE id=11");
        jdbc.update("INSERT INTO pull_task_account_action(id,tenant_id,task_id,group_execution_id,action_type,actor_group_account_id,"
                + "target_group_account_id,action_status,command_id,attempt_no,submitted_at,created_at,updated_at) "
                + "VALUES(71,7,1,11,7,101,101,5,'profile-1',1,100,100,100)");
        var prepared = new PullTaskGroupCreateTransactionService.ProfilePreparation(ref(),71L,"profile-1",1,"subject","description",100,null);
        var metadata = new GroupMetadataResult("group@g.us","subject","old-description",null,null,null,false,null,null,null,null,null,null,
                false,null,false,false,List.of());
        assertThat(creates.completeProfile(candidate(),prepared,metadata,NOW + RETRY,NOW))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertWaiting(NOW + RETRY);
        verify(profiles, never()).repairUnknownProfile(any(), any(), any(), any(), anyLong());
    }

    @Test void disabledTaskSwitchPreservesCreateAndIndefiniteProfileWait() {
        properties.setEnabled(false);
        availability(AccountRoleAvailability.Kind.TERMINAL, 1L, null, 2);
        assertThat(creates.prepareCreate(candidate(), NOW, RETRY).ready()).isTrue();
        jdbc.update("UPDATE pull_task_group_execution SET create_step=4 WHERE id=11");
        assertThat(creates.prepareProfile(candidate(), RETRY, NOW).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution",String.class)).isEqualTo("GROUP_CREATOR_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT execution_status FROM pull_task_group_execution",Integer.class)).isEqualTo(2);
        verify(lookup, never()).findRoleAvailability(any());
        verify(online, never()).reonlineReservedCreator(anyLong(), anyLong(), anyLong());
    }

    @Test void offlineCreateRejectionDoesNotIncrementBusinessFailureCount() {
        creates.failCreate(candidate(), new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE,"offline"), RETRY, NOW);
        assertWaiting(NOW + RETRY);
        assertThat(jdbc.queryForObject("SELECT create_attempt_count FROM pull_task_group_execution",Integer.class)).isZero();
    }

    @Test void offlineRejectionWithStaleOnlineSnapshotStillDoesNotCountFailure() {
        availability(AccountRoleAvailability.Kind.ONLINE, null, null, 1);
        creates.failCreate(candidate(), new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE,"offline"), RETRY, NOW);
        assertThat(jdbc.queryForObject("SELECT create_attempt_count FROM pull_task_group_execution",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution",String.class)).isEqualTo("GROUP_CREATOR_UNAVAILABLE");
    }

    @Test void disabledOfflineCreateRejectionPreservesOldFailureCounting() {
        properties.setEnabled(false);
        creates.failCreate(candidate(), new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE,"offline"), RETRY, NOW);
        assertThat(jdbc.queryForObject("SELECT create_attempt_count FROM pull_task_group_execution",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution",String.class)).isEqualTo("GROUP_CREATE_FAILED");
    }

    @Test void disabledFailurePathDoesNotReleaseReservation() {
        reserve("RESERVED");
        properties.setEnabled(false);
        failures.terminate(7L,11L,PullTaskExecutionReasonCode.GROUP_CREATOR_OFFLINE,NOW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion",Integer.class)).isOne();
    }

    @Test void completionFailureRollsBackTerminalAndReservationReleaseTogether() {
        reserve("RESERVED");
        doThrow(new IllegalStateException("completion conflict")).when(completion).completeIfTerminalByExecutionId(11L,NOW);
        assertThatThrownBy(() -> failures.terminate(7L,11L,PullTaskExecutionReasonCode.GROUP_CREATOR_OFFLINE,NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT execution_status FROM pull_task_group_execution",Integer.class)).isEqualTo(2);
    }

    private void availability(AccountRoleAvailability.Kind kind, Long since, AccountCreatorReservation reservation, int login) {
        when(lookup.findRoleAvailability(List.of(901L))).thenReturn(Map.of(901L,new AccountRoleAvailability(901L,kind,since,login,reservation)));
    }
    private static AccountCreatorReservation reservation(long tenant,long task,long execution) {
        return new AccountCreatorReservation(tenant,"RESERVED",task,execution);
    }
    private void reserve(String lifecycle) {
        jdbc.update("INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,identity_hash,creator_phone,"
                + "protocol_account_id,create_operation_id,lifecycle,created_at,updated_at) VALUES(901,7,1,11,?, '8613800000901','acc-901','create-11',?,100,100)","a".repeat(64),lifecycle);
    }
    private PullTaskGroupExecution candidate() { return executions.selectById(11L); }
    private PullTaskGroupExecution withTenantCandidate() {
        Long previous = TenantContext.get();
        TenantContext.set(7L);
        try { return candidate(); } finally { TenantContext.set(previous); }
    }
    private void reclaim(int step) { jdbc.update("UPDATE pull_task_group_execution SET create_step=?,lock_owner='worker',lock_expires_at=? WHERE id=11",step,NOW+100_000L); }
    private void assertWaiting(long nextRunAt) {
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution",String.class)).isEqualTo("GROUP_CREATOR_RECONNECTING");
        assertThat(jdbc.queryForObject("SELECT next_run_at FROM pull_task_group_execution",Long.class)).isEqualTo(nextRunAt);
    }
    private void assertFailed() {
        assertThat(jdbc.queryForObject("SELECT execution_status FROM pull_task_group_execution",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM pull_task_group_execution",String.class)).isEqualTo("GROUP_CREATOR_OFFLINE");
    }
    private static ProtocolAccountRef ref() { return new ProtocolAccountRef(901L,ProtocolBackend.WEB,"acc-901","8613800000901"); }
    private <T> T transactional(Object target,Class<T> type) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
}
