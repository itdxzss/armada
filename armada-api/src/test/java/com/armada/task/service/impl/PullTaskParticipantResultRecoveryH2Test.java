package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.scheduler.PullTaskPullWaveProgressService;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 使用真实 SQL 和服务事务复现迟到成功、残留活动指针及重复旧快照收敛。 */
@SpringJUnitConfig(PullTaskParticipantResultRecoveryH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskParticipantResultRecoveryH2Test {

    private static final long EXECUTION_ID = 501L;
    private static final long MATERIAL_ID = 601L;
    private static final long OLD_CALL_ID = 31L;
    private static final long NEW_CALL_ID = 32L;
    private static final long OLD_ATTEMPT_ID = 41L;
    private static final long NEW_ATTEMPT_ID = 42L;
    private static final String TARGET_JID = "8613800000601@s.whatsapp.net";

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskPullCallParticipantResultService service;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO pull_task_group_execution
                (id, tenant_id, task_id, seq, source_file_index, source_file_name,
                 execution_status, stage, active_pull_wave_id, created_at, updated_at)
                VALUES (501, 7, 100, 1, 1, 'recovery.txt', 2, 6, 22, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_group_account
                (id, tenant_id, task_id, group_execution_id, account_id, account_phone,
                 role_type, role_seq, membership_status, availability_status, created_at, updated_at)
                VALUES (901, 7, 100, 501, 500, '8613800000500', 2, 1, 2, 1, 1, 1)
                """);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void lateSuccessRemovesOnlyWinningMemberFromThirteenPersonRetryPlan() {
        insertCalls(13, PullTaskPullCallStatus.PLANNED);
        insertWinningMember(PullTaskMaterialPullStatus.UNCONSUMED, NEW_CALL_ID);
        insertAttempts(PullTaskParticipantAttemptStatus.PLANNED);
        for (int seq = 2; seq <= 13; seq++) {
            long materialId = 600L + seq;
            long attemptId = 50L + seq;
            String phone = "8613800000" + materialId;
            jdbc.update("""
                    INSERT INTO pull_task_material_member
                    (id, tenant_id, group_execution_id, member_seq, source_line_no,
                     normalized_phone, pull_call_id, active_pull_attempt_id, created_at, updated_at)
                    VALUES (?, 7, 501, ?, ?, ?, 32, ?, 1, 1)
                    """, materialId, seq, seq, phone, attemptId);
            jdbc.update("""
                    INSERT INTO pull_task_pull_call_member_attempt
                    (id, tenant_id, task_id, group_execution_id, pull_call_id, pull_wave_id,
                     participant_type, participant_ref_id, target_phone, target_jid,
                     puller_group_account_id, attempt_no, lifecycle_status, active_slot,
                     created_at, updated_at)
                    VALUES (?, 7, 100, 501, 32, 22, 1, ?, ?, ?, 901, 1, 1, 1, 1, 1)
                    """, attemptId, materialId, phone, phone + "@s.whatsapp.net");
        }

        assertThat(service.handle(lateSuccess())).isTrue();

        assertSuccessfulMember(6_000L);
        assertThat(attemptMapper.selectById(OLD_ATTEMPT_ID).getProtocolOutcome())
                .isEqualTo("SUCCESS");
        assertThat(attemptMapper.selectById(NEW_ATTEMPT_ID)).satisfies(attempt -> {
            assertThat(attempt.getLifecycleStatus())
                    .isEqualTo(PullTaskParticipantAttemptStatus.CANCELED.code());
            assertThat(attempt.getActiveSlot()).isNull();
            assertThat(attempt.getSubmittedAt()).isNull();
        });
        PullTaskPullCall retry = findCall(NEW_CALL_ID);
        assertThat(retry.getPlannedMaterialCount()).isEqualTo(12);
        assertThat(retry.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(retry.getCommandId()).isNull();
        assertThat(materialMapper.selectByExecution(EXECUTION_ID).stream()
                .filter(member -> Long.valueOf(NEW_CALL_ID).equals(member.getPullCallId()))
                .toList()).hasSize(12);
        assertThat(attemptMapper.selectByCall(NEW_CALL_ID).stream()
                .filter(row -> row.getLifecycleStatus()
                        == PullTaskParticipantAttemptStatus.PLANNED.code())
                .toList()).hasSize(12);

        assertThat(service.handle(lateSuccess())).isTrue();
        assertThat(findCall(NEW_CALL_ID).getPlannedMaterialCount()).isEqualTo(12);
        assertSuccessfulMember(6_000L);
    }

    @Test
    void unconfirmedSettlementKeepsOldSuccessAndClosesCurrentSubmittedAttempt() {
        seedSuccessWithSubmittedAttempt();

        assertThat(service.settleUncertain(staleSettlement(7L))).isTrue();

        assertSuccessfulMember(1_500L);
        assertThat(attemptMapper.selectById(NEW_ATTEMPT_ID)).satisfies(attempt -> {
            assertThat(attempt.getLifecycleStatus())
                    .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
            assertThat(attempt.getActiveSlot()).isNull();
            assertThat(attempt.getProtocolOutcome()).isEqualTo("UNKNOWN");
        });
        assertThat(findCall(NEW_CALL_ID).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(findCall(NEW_CALL_ID).getResultAt()).isEqualTo(7_000L);
    }

    @Test
    void repeatedOldSettlementSnapshotDoesNotRewriteTerminalResultOrCrossTenant() {
        seedSuccessWithSubmittedAttempt();
        PullTaskUncertainParticipantSettlement snapshot = staleSettlement(7L);
        PullTaskUncertainParticipantSettlement otherTenant = staleSettlement(8L);

        assertThat(service.settleUncertain(otherTenant)).isFalse();
        assertThat(TenantContext.get()).isEqualTo(7L);
        assertThat(attemptMapper.selectById(NEW_ATTEMPT_ID).getLifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.SUBMITTED.code());
        assertThat(service.settleUncertain(snapshot)).isTrue();
        assertThat(service.settleUncertain(snapshot)).isFalse();

        assertSuccessfulMember(1_500L);
        assertThat(attemptMapper.selectById(NEW_ATTEMPT_ID).getResultAt()).isEqualTo(7_000L);
        assertThat(findCall(NEW_CALL_ID).getResultAt()).isEqualTo(7_000L);
        assertThat(attemptMapper.selectByCall(NEW_CALL_ID)).hasSize(1);
    }

    private void seedSuccessWithSubmittedAttempt() {
        insertCalls(1, PullTaskPullCallStatus.SUBMITTED);
        insertWinningMember(PullTaskMaterialPullStatus.SUCCESS, OLD_CALL_ID);
        insertAttempts(PullTaskParticipantAttemptStatus.SUBMITTED);
        jdbc.update("""
                UPDATE pull_task_pull_call_member_attempt SET lifecycle_status = 3,
                protocol_outcome = 'SUCCESS', execution_state = 'STARTED', result_at = 1500
                WHERE id = 41
                """);
    }

    private void insertCalls(int plannedCount, PullTaskPullCallStatus currentStatus) {
        jdbc.update("""
                INSERT INTO pull_task_pull_call
                (id, tenant_id, task_id, group_execution_id, pull_wave_id, call_seq,
                 puller_group_account_id, puller_account_id, planned_material_count,
                 planned_station_count, call_status, command_id, idempotency_key,
                 submitted_at, created_at, updated_at)
                VALUES (31, 7, 100, 501, 21, 1, 901, 500, 1, 0, 3, 'cmd-old', 'old', 1000, 1, 1),
                       (32, 7, 100, 501, 22, 2, 901, 500, ?, 0, ?, ?, 'new', ?, 1, 1)
                """, plannedCount, currentStatus.code(),
                currentStatus == PullTaskPullCallStatus.SUBMITTED ? "cmd-new" : null,
                currentStatus == PullTaskPullCallStatus.SUBMITTED ? 2_000L : null);
    }

    private void insertWinningMember(PullTaskMaterialPullStatus status, long callId) {
        jdbc.update("""
                INSERT INTO pull_task_material_member
                (id, tenant_id, group_execution_id, member_seq, source_line_no,
                 normalized_phone, pull_call_id, pull_status, active_pull_attempt_id,
                 wa_jid, pull_result_at, created_at, updated_at)
                VALUES (601, 7, 501, 1, 1, '8613800000601', ?, ?, 42, ?, ?, 1, 1)
                """, callId, status.code(),
                status == PullTaskMaterialPullStatus.SUCCESS ? TARGET_JID : null,
                status == PullTaskMaterialPullStatus.SUCCESS ? 1_500L : null);
    }

    private void insertAttempts(PullTaskParticipantAttemptStatus currentStatus) {
        jdbc.update("""
                INSERT INTO pull_task_pull_call_member_attempt
                (id, tenant_id, task_id, group_execution_id, pull_call_id, pull_wave_id,
                 participant_type, participant_ref_id, target_phone, target_jid,
                 puller_group_account_id, attempt_no, lifecycle_status, active_slot,
                 protocol_outcome, execution_state, submitted_at, released_at, created_at, updated_at)
                VALUES (41, 7, 100, 501, 31, 21, 1, 601, '8613800000601', ?, 901,
                        1, 4, NULL, 'UNKNOWN', 'UNCERTAIN', 1000, 1200, 1, 1),
                       (42, 7, 100, 501, 32, 22, 1, 601, '8613800000601', ?, 901,
                        2, ?, 1, NULL, NULL, ?, NULL, 1, 1)
                """, TARGET_JID, TARGET_JID, currentStatus.code(),
                currentStatus == PullTaskParticipantAttemptStatus.SUBMITTED ? 2_000L : null);
    }

    private PullTaskUncertainParticipantSettlement staleSettlement(long tenantId) {
        return new PullTaskUncertainParticipantSettlement(
                new PullTaskUncertainParticipantSettlement.Context(
                        tenantId, findCall(NEW_CALL_ID), executionMapper.selectById(EXECUTION_ID)),
                attemptMapper.selectById(NEW_ATTEMPT_ID),
                PullTaskRosterObservation.UNCONFIRMED, 7_000L);
    }

    private PullTaskPullCall findCall(long callId) {
        return callMapper.selectByExecution(EXECUTION_ID).stream()
                .filter(call -> call.getId() == callId).findFirst().orElseThrow();
    }

    private void assertSuccessfulMember(long expectedResultAt) {
        PullTaskMaterialMember saved = materialMapper.selectByExecution(EXECUTION_ID).stream()
                .filter(member -> member.getId() == MATERIAL_ID).findFirst().orElseThrow();
        assertThat(saved.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(saved.getPullCallId()).isEqualTo(OLD_CALL_ID);
        assertThat(saved.getActivePullAttemptId()).isNull();
        assertThat(saved.getPullFailureCount()).isZero();
        assertThat(saved.getWaJid()).isEqualTo(TARGET_JID);
        assertThat(saved.getPullResultAt()).isEqualTo(expectedResultAt);
    }

    private PullTaskBatchParticipantCallback lateSuccess() {
        return new PullTaskBatchParticipantCallback(
                7L, 100L, EXECUTION_ID, OLD_CALL_ID, 500L, "acc_test", "cmd-old", 1,
                TARGET_JID, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, null, null, false, 6_000L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("participant_result_recovery_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskGroupExecutionMapper executions(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materials(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullCallMapper calls(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attempts(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean PullTaskUnknownResultResources resources(SqlSessionTemplate template) {
            return new PullTaskUnknownResultResources(
                    template.getMapper(PullTaskAccountActionMapper.class),
                    template.getMapper(PullTaskPullCallMapper.class),
                    template.getMapper(PullTaskPullCallMemberAttemptMapper.class),
                    template.getMapper(PullTaskMaterialMemberMapper.class),
                    template.getMapper(PullTaskGroupAccountMapper.class));
        }

        @Bean PullTaskPullCallParticipantResultService service(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executions,
                ApplicationEventPublisher publisher) {
            return new PullTaskPullCallParticipantResultService(resources, executions,
                    mock(AccountOperationRestrictionService.class),
                    new PullTaskPullCallResultCoordination(
                            mock(PullTaskStickyPullerTransactionService.class),
                            mock(PullTaskGroupExecutionFailureService.class),
                            mock(PullTaskPullWaveProgressService.class)), publisher);
        }
    }
}
