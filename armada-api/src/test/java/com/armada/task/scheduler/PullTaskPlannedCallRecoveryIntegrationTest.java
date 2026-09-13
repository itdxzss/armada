package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskExecutionStage;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 复现迟到成功遗留的 13/12 计划不一致，并使用真实 Mapper 和 Spring 事务验证恢复。 */
@SpringJUnitConfig({PullTaskPullWaveDispatchIntegrationTest.TestConfig.class,
        PullTaskPlannedCallRecoveryIntegrationTest.RecoveryConfig.class})
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPlannedCallRecoveryIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskBatchAddTransactionService service;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private PullTaskPullCallMapper calls;
    @Autowired private PullTaskPullCallMemberAttemptMapper attempts;
    @Autowired private PullTaskMaterialMemberMapper materials;
    @Autowired private PullTaskPullWaveMapper waves;
    @Autowired private AccountProtocolLookupService lookup;
    @Autowired private ProtocolCommandOutboxService outbox;
    @Autowired private PullTaskPullExecutionProcessor processor;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        reset(lookup, outbox);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO pull_task (id,tenant_id,task_type,task_name,mode,status,"
                + "config_json,created_at,updated_at) VALUES "
                + "(100,7,'STANDARD','task','NORMAL_LINK','EXECUTING','{}',100,100)");
        jdbc.update("INSERT INTO pull_task_standard_setting "
                + "(tenant_id,task_id,auto_start,material_admin_timing,pull_count_min,pull_count_max,"
                + "pull_interval_seconds,puller_count_per_group,station_count_per_call,"
                + "concurrent_group_count,puller_risk_minutes,required_manager_count,"
                + "manager_group_id,puller_group_id,manager_group_name,puller_group_name,"
                + "created_at,updated_at) VALUES "
                + "(7,100,1,1,10,20,15,2,0,10,0,1,88,89,'manager','puller',100,100)");
        jdbc.update("INSERT INTO pull_task_group_execution "
                + "(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,"
                + "stage,active_pull_wave_id,lock_owner,lock_expires_at,created_at,updated_at) "
                + "VALUES (11,7,100,1,1,'AK03.txt',2,?,21,'worker',10000,100,100)",
                PullTaskExecutionStage.PULL_EXECUTION.code());
        jdbc.update("INSERT INTO pull_task_pull_wave "
                + "(id,tenant_id,task_id,group_execution_id,wave_no,wave_type,wave_status,"
                + "planned_call_count,next_call_seq,next_dispatch_at,created_at,updated_at) "
                + "VALUES (21,7,100,11,2,2,1,1,1,500,100,100)");
        jdbc.update("INSERT INTO pull_task_group_account "
                + "(id,tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,"
                + "role_seq,membership_status,created_at,updated_at) "
                + "VALUES (41,7,100,11,902,'8613800000902',2,1,2,100,100)");
        jdbc.update("INSERT INTO pull_task_pull_call "
                + "(id,tenant_id,task_id,group_execution_id,pull_wave_id,call_seq,wave_call_seq,"
                + "puller_group_account_id,puller_account_id,puller_assignment_seq,"
                + "planned_material_count,planned_station_count,idempotency_key,created_at,updated_at) "
                + "VALUES (31,7,100,11,21,2,1,41,902,1,13,0,'unchanged-call',100,100)");
        for (int index = 1; index <= 13; index++) {
            String phone = "861390000" + String.format("%04d", index);
            jdbc.update("INSERT INTO pull_task_material_member "
                    + "(id,tenant_id,group_execution_id,member_seq,source_line_no,normalized_phone,"
                    + "pull_call_id,active_pull_attempt_id,created_at,updated_at) "
                    + "VALUES (?,7,11,?,?,?,31,?,100,100)", index, index, index, phone, 100 + index);
            jdbc.update("INSERT INTO pull_task_pull_call_member_attempt "
                    + "(id,tenant_id,task_id,group_execution_id,pull_call_id,pull_wave_id,"
                    + "participant_type,participant_ref_id,target_phone,target_jid,"
                    + "puller_group_account_id,puller_assignment_seq,attempt_no,created_at,updated_at) "
                    + "VALUES (?,7,100,11,31,21,1,?,?,?,41,1,2,100,100)",
                    100 + index, index, phone, phone + "@s.whatsapp.net");
        }
        succeedFirstMaterial();
        when(lookup.findEligiblePullerProtocolRefs(anyList())).thenReturn(List.of(
                new ProtocolAccountRef(902L, ProtocolBackend.WEB, "puller-902", "8613800000902")));
        when(outbox.enqueuePullTaskBatchAddCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("task:100", List.of("cmd-recovered"), 1));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void repairsThirteenTwelveMismatchAndSubmitsOnlyRemainingTwelve() {
        assertThat(prepare()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskPullCall call = calls.selectByWaveAndSeq(21, 1);
        assertThat(call.getCommandId()).isEqualTo("cmd-recovered");
        assertThat(call.getPlannedMaterialCount()).isEqualTo(12);
        assertThat(call.getIdempotencyKey()).isEqualTo("unchanged-call");
        assertThat(attempts.selectByCall(31)).hasSize(13);
        assertThat(attempts.selectById(101).getLifecycleStatus()).isEqualTo(5);
        assertThat(attempts.selectById(101).getActiveSlot()).isNull();
        assertThat(attempts.selectByCallAndStatus(31, 2)).hasSize(12);
        assertSuccessUnchanged();
        assertThat(waves.selectById(21).getNextCallSeq()).isEqualTo(2);
        assertThat(waves.selectById(21).getWaveStatus()).isEqualTo(2);
    }

    @Test
    void allSuccessfulParticipantsCancelEmptyCallAndAdvanceWithoutOutbox() {
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2,pull_call_id=30,"
                + "wa_jid=CONCAT(normalized_phone,'@s.whatsapp.net'),pull_result_at=450");

        assertThat(prepare()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(calls.selectByWaveAndSeq(21, 1).getCallStatus()).isEqualTo(5);
        assertThat(calls.selectByWaveAndSeq(21, 1).getPlannedMaterialCount()).isZero();
        assertThat(attempts.selectByCall(31)).extracting(
                PullTaskPullCallMemberAttempt::getLifecycleStatus).containsOnly(5);
        assertThat(waves.selectById(21).getNextCallSeq()).isEqualTo(2);
        assertThat(waves.selectById(21).getWaveStatus()).isEqualTo(2);
        verifyNoInteractions(outbox);
    }

    @Test
    void commandAssignedCallIsNeverReplannedEvenIfStatusIsStale() {
        jdbc.update("UPDATE pull_task_pull_call SET command_id='already-enqueued' WHERE id=31");

        assertThat(prepare()).isEqualTo(PullTaskExecutionDispatchResult.LOST);

        assertThat(calls.selectByWaveAndSeq(21, 1).getPlannedMaterialCount()).isEqualTo(13);
        assertThat(attempts.selectById(101).getLifecycleStatus()).isEqualTo(1);
        verifyNoInteractions(outbox);
    }

    @Test
    void existingPlansBeyondRetryBudgetBecomeUnknownAndNeverSubmit() {
        jdbc.update("UPDATE pull_task_pull_call_member_attempt SET attempt_no=5 WHERE pull_call_id=31");
        jdbc.update("UPDATE pull_task_material_member SET pull_failure_count=3 WHERE id<>1");

        assertThat(prepare()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertSuccessUnchanged();
        assertThat(materials.selectByExecution(11)).filteredOn(row -> row.getId() != 1L)
                .allSatisfy(row -> {
                    assertThat(row.getPullStatus()).isEqualTo(4);
                    assertThat(row.getPullFailureCount()).isEqualTo(3);
                    assertThat(row.getActivePullAttemptId()).isNull();
                    assertThat(row.getPullReasonCode()).isEqualTo("RETRY_LIMIT_REACHED");
                });
        assertThat(attempts.selectByCall(31)).extracting(
                PullTaskPullCallMemberAttempt::getLifecycleStatus).containsOnly(5);
        assertThat(calls.selectByWaveAndSeq(21, 1).getPlannedMaterialCount()).isZero();
        assertThat(waves.selectById(21).getWaveStatus()).isEqualTo(2);
        verifyNoInteractions(outbox);
    }

    @Test
    void preflightSkipsOverBudgetPlanWithoutAnyAvailablePuller() {
        jdbc.update("UPDATE pull_task_pull_call_member_attempt SET attempt_no=5 WHERE pull_call_id=31");
        jdbc.update("DELETE FROM pull_task_group_account WHERE id=41");
        when(lookup.findEligiblePullerProtocolRefs(anyList())).thenReturn(List.of());

        assertThat(processor.process(executions.selectById(11), "worker", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertSuccessUnchanged();
        assertThat(calls.selectByWaveAndSeq(21, 1).getCallStatus()).isEqualTo(5);
        assertThat(waves.selectById(21).getWaveStatus()).isEqualTo(2);
        assertThat(executions.selectById(11).getExecutionStatus()).isEqualTo(2);
        verifyNoInteractions(lookup, outbox);
    }

    @Test
    void skippedIntermediateCallAddsNoProtocolDelay() {
        jdbc.update("UPDATE pull_task_pull_wave SET planned_call_count=2 WHERE id=21");
        jdbc.update("UPDATE pull_task_pull_call_member_attempt SET attempt_no=5 WHERE pull_call_id=31");
        jdbc.update("INSERT INTO pull_task_pull_call "
                + "(id,tenant_id,task_id,group_execution_id,pull_wave_id,call_seq,wave_call_seq,"
                + "planned_material_count,planned_station_count,idempotency_key,created_at,updated_at) "
                + "VALUES (32,7,100,11,21,3,2,0,0,'next-empty-call',100,100)");

        assertThat(service.preflight(executions.selectById(11), calls.selectByWaveAndSeq(21, 1),
                "worker", 1_000L).result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(waves.selectById(21).getNextCallSeq()).isEqualTo(2);
        assertThat(waves.selectById(21).getWaveStatus()).isEqualTo(1);
        assertThat(waves.selectById(21).getNextDispatchAt()).isEqualTo(1_000L);
        assertThat(executions.selectById(11).getNextRunAt()).isEqualTo(1_000L);
        verifyNoInteractions(lookup, outbox);
    }

    @Test
    void invalidUnsuccessfulBindingDoesNotGetSilentlyPruned() {
        jdbc.update("UPDATE pull_task_material_member SET pull_call_id=NULL,"
                + "active_pull_attempt_id=NULL WHERE id=2");

        assertThat(prepare()).isEqualTo(PullTaskExecutionDispatchResult.LOST);

        assertThat(calls.selectByWaveAndSeq(21, 1).getPlannedMaterialCount()).isEqualTo(13);
        assertThat(attempts.selectById(102).getLifecycleStatus()).isEqualTo(1);
        verifyNoInteractions(outbox);
    }

    @Test
    void outboxFailureRollsBackRecoveryAndCheckpointTogether() {
        when(outbox.enqueuePullTaskBatchAddCommands(anyList()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(this::prepare).isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(calls.selectByWaveAndSeq(21, 1).getPlannedMaterialCount()).isEqualTo(13);
        assertThat(attempts.selectById(101).getLifecycleStatus()).isEqualTo(1);
        assertThat(materials.selectByExecution(11).get(0).getActivePullAttemptId()).isEqualTo(101);
        assertThat(waves.selectById(21).getNextCallSeq()).isEqualTo(1);
    }

    private PullTaskExecutionDispatchResult prepare() {
        return service.prepare(executions.selectById(11), calls.selectByWaveAndSeq(21, 1),
                "worker", 1_000L);
    }

    private void succeedFirstMaterial() {
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2,pull_call_id=30,"
                + "wa_jid='8613900000001@s.whatsapp.net',pull_result_at=450 WHERE id=1");
    }

    private void assertSuccessUnchanged() {
        PullTaskMaterialMember success = materials.selectByExecution(11).get(0);
        assertThat(success.getPullStatus()).isEqualTo(2);
        assertThat(success.getPullCallId()).isEqualTo(30);
        assertThat(success.getPullResultAt()).isEqualTo(450);
        assertThat(success.getWaJid()).isEqualTo("8613900000001@s.whatsapp.net");
        assertThat(success.getActivePullAttemptId()).isNull();
        assertThat(success.getPullReasonCode()).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class RecoveryConfig {
        @Bean PullTaskBatchAddTransactionService recoveryTransactions(
                PullTaskMapper tasks, PullTaskStandardSettingMapper settings,
                PullTaskGroupAccountMapper accounts, PullTaskMaterialMemberMapper materials,
                PullTaskBatchAddResources resources) {
            return new PullTaskBatchAddTransactionService(tasks, settings, accounts, materials, resources);
        }

        @Bean
        @Primary
        PullTaskBatchAddProcessor recoveryBatchProcessor(PullTaskBatchAddTransactionService transactions) {
            return new PullTaskBatchAddProcessor(transactions);
        }
    }

}
