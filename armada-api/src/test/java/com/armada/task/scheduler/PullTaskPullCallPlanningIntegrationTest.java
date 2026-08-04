package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
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
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskMaterialPullResult;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import com.armada.task.service.impl.PullTaskProtocolResultCallbackServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** EX-06 使用真实 Mapper XML 验证调用计划、末尾余量和站台不足原子性。 */
@SpringJUnitConfig(PullTaskPullCallPlanningIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullCallPlanningIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper pullCallMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private PullTaskPullCallPlanningTransactionService service;
    @Autowired private PullTaskBatchAddTransactionService batchService;
    @Autowired private PullTaskProtocolResultCallbackService callbackService;
    @Autowired private ProtocolCommandOutboxService outboxService;

    private Long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
        AtomicInteger commandSequence = new AtomicInteger();
        when(outboxService.enqueuePullTaskBatchAddCommands(anyList()))
                .thenAnswer(invocation -> new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100",
                        List.of("cmd-batch-" + commandSequence.incrementAndGet()), 1));
        when(accountLookup.findOnlineNormalByGroupId(89L))
                .thenReturn(List.of(new ProtocolAccountRef(
                        902L, ProtocolBackend.WEB, "puller-902", "8613800000902")));
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
                + "VALUES (7, 100, 1, 1, 2, 2, 1, 1, 1, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
        PullTaskGroupExecution execution = draft();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(100L, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution "
                + "SET execution_status=2, stage=5, version=6, group_jid='120363group@g.us' "
                + "WHERE id=" + executionId);
        PullTaskGroupAccount puller = puller();
        groupAccountMapper.insert(puller);
        groupAccountMapper.updateMembership(puller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        materialMapper.batchInsert(List.of(material(1), material(2), material(3)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void plansConfiguredBatchThenConsumesFinalRemainderWithoutDroppingIt() {
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(
                account(911L), account(912L)));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);

        PullTaskPullCall first = service
                .prepare(firstCandidate, "worker-1", 610L).call();

        TenantContext.set(7L);
        assertThat(first.getPlannedMaterialCount()).isEqualTo(2);
        assertThat(first.getPlannedStationCount()).isEqualTo(1);
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> first.getId().equals(row.getPullCallId()))
                .hasSize(2);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getPullCallId)
                .isEqualTo(first.getId());

        finishPlannedCall(first, 700L);
        PullTaskGroupExecution secondCandidate = claim("worker-2", 800L, 1_100L);
        PullTaskPullCall second = service
                .prepare(secondCandidate, "worker-2", 810L).call();

        TenantContext.set(7L);
        assertThat(second.getCallSeq()).isEqualTo(2);
        assertThat(second.getPlannedMaterialCount()).isEqualTo(1);
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> second.getId().equals(row.getPullCallId()))
                .singleElement();
    }

    @Test
    void stationShortageWaitsThisRowWithoutCreatingHalfCallOrConsumingMaterial() {
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of());
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        PullTaskPullCallPreparation result = service.prepare(candidate, "worker-1", 610L);

        assertThat(result.ready()).isFalse();
        assertThat(result.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(pullCallMapper.selectByExecution(executionId)).isEmpty();
        assertThat(materialMapper.selectByExecution(executionId))
                .allMatch(row -> row.getPullCallId() == null
                        && row.getPullStatus() == PullTaskMaterialPullStatus.UNCONSUMED.code());
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.STATION.code());
        assertThat(saved.getReasonMessage()).isEqualTo("当前可用站台不足，缺口人数=1");
    }

    @Test
    void stationSelectionExcludesPhonesAlreadyFrozenAsMaterials() {
        ProtocolAccountRef conflicting = new ProtocolAccountRef(
                911L, ProtocolBackend.WEB, "protocol-911", "8613900000001");
        ProtocolAccountRef alternative = account(912L);
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(conflicting, alternative));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();

        assertThat(call).isNotNull();
        TenantContext.set(7L);
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getAccountId)
                .isEqualTo(912L);
    }

    @Test
    void supplementalPullerFromAnotherGroupParticipatesInFutureRotation()
            throws SQLException {
        execute("UPDATE pull_task_group_account SET availability_status=3, released_at=580 "
                + "WHERE group_execution_id=" + executionId + " AND account_id=902");
        PullTaskGroupAccount supplement = puller(903L, 2);
        supplement.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        groupAccountMapper.insert(supplement);
        groupAccountMapper.updateMembership(supplement.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of());
        when(accountLookup.findActiveProtocolRefs(List.of(903L)))
                .thenReturn(List.of(account(903L)));
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);

        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();

        assertThat(call).isNotNull();
        assertThat(call.getPullerAccountId()).isEqualTo(903L);
    }

    @Test
    void writesStationAndMaterialResultsThenKeepsRemainingMaterialSchedulable() {
        ProtocolAccountRef pullerRef = new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "puller-902", "8613800000902");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L))).thenReturn(List.of(pullerRef));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(call, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskBatchParticipantProtocolOutcome.FAILED, "PRIVACY", 630L);

        TenantContext.set(7L);
        PullTaskPullCall savedCall = pullCallMapper.selectByExecution(executionId).get(0);
        assertThat(savedCall.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> call.getId().equals(row.getPullCallId()))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.FAILED.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getMembershipStatus)
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        PullTaskGroupExecution savedExecution = executionMapper.selectByTaskId(100L).get(0);
        assertThat(savedExecution.getStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(savedExecution.getLockOwner()).isNull();
    }

    @Test
    void immediateAdminTimingPausesFurtherPullsForJoinedFlaggedMaterial() throws SQLException {
        execute("UPDATE pull_task_material_member "
                + "SET admin_required=1, admin_status=1 WHERE group_execution_id="
                + executionId + " AND member_seq=1");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L)))
                .thenReturn(List.of(account(902L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(call, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskBatchParticipantProtocolOutcome.SUCCESS, null, 630L);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        assertThat(materialMapper.selectUnconsumed(executionId, 10)).isNotEmpty();
        assertThat(materialMapper.selectPendingAdmin(
                executionId, 1, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code())).hasSize(1);
    }

    @Test
    void afterGroupDoneTimingKeepsPullingWhileMaterialsRemain() throws SQLException {
        execute("UPDATE pull_task_standard_setting "
                + "SET material_admin_timing=2 WHERE task_id=100");
        execute("UPDATE pull_task_material_member "
                + "SET admin_required=1, admin_status=1 WHERE group_execution_id="
                + executionId + " AND member_seq=1");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L)))
                .thenReturn(List.of(account(902L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(call, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskBatchParticipantProtocolOutcome.SUCCESS, null, 630L);

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L).get(0).getStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(materialMapper.selectUnconsumed(executionId, 10)).isNotEmpty();
    }

    @Test
    void pullerCursorSurvivesCheckpointAndSelectsTheNextPuller() throws SQLException {
        execute("UPDATE pull_task_standard_setting "
                + "SET puller_count_per_group=2, pull_interval_seconds=0 WHERE task_id=100");
        PullTaskGroupAccount secondPuller = puller(903L, 2);
        groupAccountMapper.insert(secondPuller);
        groupAccountMapper.updateMembership(secondPuller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        ProtocolAccountRef firstRef = account(902L);
        ProtocolAccountRef secondRef = account(903L);
        when(accountLookup.findOnlineNormalByGroupId(89L))
                .thenReturn(List.of(firstRef, secondRef));
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L), account(912L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L, 903L)))
                .thenReturn(List.of(firstRef, secondRef));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall firstCall = service
                .prepare(firstCandidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(firstCandidate, firstCall, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(firstCall, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskBatchParticipantProtocolOutcome.SUCCESS, null, 630L);

        PullTaskGroupExecution secondCandidate = claim("worker-2", 700L, 1_000L);
        PullTaskPullCall secondCall = service
                .prepare(secondCandidate, "worker-2", 710L).call();

        assertThat(firstCall.getPullerAccountId()).isEqualTo(902L);
        assertThat(secondCall.getPullerAccountId()).isEqualTo(903L);
        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNextPullerIndex())
                .isEqualTo(2);
    }

    @Test
    void pullerCursorUsesStableRoleSequenceWhenAvailableSetChanges() throws SQLException {
        execute("UPDATE pull_task_standard_setting "
                + "SET puller_count_per_group=3, pull_interval_seconds=0 WHERE task_id=100");
        PullTaskGroupAccount secondPuller = puller(903L, 2);
        PullTaskGroupAccount thirdPuller = puller(904L, 3);
        groupAccountMapper.insert(secondPuller);
        groupAccountMapper.insert(thirdPuller);
        groupAccountMapper.updateMembership(secondPuller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        groupAccountMapper.updateMembership(thirdPuller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        ProtocolAccountRef firstRef = account(902L);
        ProtocolAccountRef secondRef = account(903L);
        ProtocolAccountRef thirdRef = account(904L);
        when(accountLookup.findOnlineNormalByGroupId(89L))
                .thenReturn(List.of(firstRef, secondRef, thirdRef));
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L), account(912L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L, 903L, 904L)))
                .thenReturn(List.of(firstRef, secondRef, thirdRef));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall firstCall = service
                .prepare(firstCandidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(firstCandidate, firstCall, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(firstCall, PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskBatchParticipantProtocolOutcome.SUCCESS, null, 630L);
        execute("UPDATE pull_task_group_account SET availability_status=3 "
                + "WHERE group_execution_id=" + executionId + " AND account_id=902");

        PullTaskGroupExecution secondCandidate = claim("worker-2", 700L, 1_000L);
        PullTaskPullCall secondCall = service
                .prepare(secondCandidate, "worker-2", 710L).call();

        assertThat(firstCall.getPullerAccountId()).isEqualTo(902L);
        assertThat(secondCall.getPullerAccountId()).isEqualTo(903L);
    }

    @Test
    void offlinePullerReassignsAndResubmitsTheSameFrozenCall() {
        PullTaskGroupAccount secondPuller = puller(903L, 2);
        groupAccountMapper.insert(secondPuller);
        groupAccountMapper.updateMembership(secondPuller.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        ProtocolAccountRef firstRef = account(902L);
        ProtocolAccountRef secondRef = account(903L);
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L, 903L)))
                .thenReturn(List.of(firstRef, secondRef));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall frozen = service.prepare(firstCandidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(firstCandidate, frozen, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskPullCall firstSubmission = pullCallMapper.selectByExecution(executionId).get(0);
        PullTaskMaterialMember frozenMaterial = materialMapper.selectByExecution(executionId)
                .stream().filter(row -> frozen.getId().equals(row.getPullCallId()))
                .findFirst().orElseThrow();

        assertThat(callbackService.handlePullCallParticipant(
                new PullTaskBatchParticipantCallback(
                        7L, 100L, executionId, frozen.getId(), 902L,
                        "protocol-902", firstSubmission.getCommandId(), 1,
                        frozenMaterial.getNormalizedPhone() + "@s.whatsapp.net",
                        PullTaskBatchParticipantProtocolOutcome.FAILED,
                        "ACCOUNT_NOT_ONLINE", "offline", false, 630L)))
                .isTrue();

        TenantContext.set(7L);
        PullTaskPullCall reset = pullCallMapper.selectByExecution(executionId).get(0);
        assertThat(reset.getId()).isEqualTo(frozen.getId());
        assertThat(reset.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> frozen.getId().equals(row.getPullCallId()))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.SUBMITTED.code());

        when(accountLookup.findActiveProtocolRefs(List.of(903L)))
                .thenReturn(List.of(secondRef));
        PullTaskGroupExecution reassignCandidate = claim("worker-2", 640L, 900L);
        PullTaskPullCall samePlan = service
                .prepare(reassignCandidate, "worker-2", 650L).call();
        assertThat(samePlan.getId()).isEqualTo(frozen.getId());
        assertThat(batchService.prepare(reassignCandidate, samePlan, "worker-2", 660L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        PullTaskGroupExecution submitCandidate = claim("worker-3", 670L, 900L);
        PullTaskPullCall reassigned = service
                .prepare(submitCandidate, "worker-3", 680L).call();
        assertThat(batchService.prepare(submitCandidate, reassigned, "worker-3", 690L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        PullTaskPullCall secondSubmission = pullCallMapper.selectByExecution(executionId).get(0);
        assertThat(secondSubmission.getId()).isEqualTo(frozen.getId());
        assertThat(secondSubmission.getPullerAccountId()).isEqualTo(903L);
        assertThat(secondSubmission.getCommandId()).isNotEqualTo(firstSubmission.getCommandId());
        assertThat(secondSubmission.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());
    }

    @Test
    void samePullerCallIsDeferredUntilItsConfiguredAccountInterval() {
        when(accountLookup.findOnlineNormalByGroupId(90L))
                .thenReturn(List.of(account(911L), account(912L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L)))
                .thenReturn(List.of(account(902L)));
        PullTaskGroupExecution firstCandidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall first = service
                .prepare(firstCandidate, "worker-1", 610L).call();
        TenantContext.set(7L);
        finishPlannedCall(first, 700L);
        PullTaskGroupExecution secondCandidate = claim("worker-2", 800L, 1_100L);
        PullTaskPullCall second = service
                .prepare(secondCandidate, "worker-2", 810L).call();

        PullTaskExecutionDispatchResult result =
                batchService.prepare(secondCandidate, second, "worker-2", 900L);

        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(pullCallMapper.selectByExecution(executionId).get(1).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNextRunAt())
                .isEqualTo(1_700L);
    }

    @Test
    void rateLimitedPullerEntersConfiguredRiskCooldown() throws SQLException {
        execute("UPDATE pull_task_standard_setting "
                + "SET puller_risk_minutes=5 WHERE task_id=100");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L)))
                .thenReturn(List.of(account(902L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(call, PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN, "RATE_LIMITED", 630L);

        TenantContext.set(7L);
        PullTaskGroupAccount saved = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(saved.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.RISK_COOLDOWN.code());
        assertThat(saved.getCooldownUntil()).isEqualTo(300_630L);
    }

    @Test
    void offlineFrozenPullerIsSkippedForAnotherLivePuller() {
        PullTaskGroupAccount replacement = puller(903L, 2);
        groupAccountMapper.insert(replacement);
        groupAccountMapper.updateMembership(replacement.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        ProtocolAccountRef replacementRef = new ProtocolAccountRef(
                903L, ProtocolBackend.WEB, "puller-903", "8613800000903");
        when(accountLookup.findOnlineNormalByGroupId(89L)).thenReturn(List.of(
                new ProtocolAccountRef(
                        902L, ProtocolBackend.WEB, "puller-902", "8613800000902"),
                replacementRef));
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L, 903L)))
                .thenReturn(List.of(replacementRef));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();

        PullTaskExecutionDispatchResult prepared =
                batchService.prepare(candidate, call, "worker-1", 620L);

        assertThat(prepared).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskPullCall saved = pullCallMapper.selectByExecution(executionId).get(0);
        assertThat(saved.getPullerGroupAccountId()).isEqualTo(replacement.getId());
        assertThat(saved.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()).get(0)
                .getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
    }

    @Test
    void noLivePullerMovesExecutionToResourceWaitingWithoutHotLoop() {
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L))).thenReturn(List.of());
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();

        PullTaskExecutionDispatchResult prepared =
                batchService.prepare(candidate, call, "worker-1", 620L);

        assertThat(prepared).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(saved.getWaitResourceType()).isEqualTo(PullTaskWaitResourceType.PULLER.code());
        assertThat(saved.getLockOwner()).isNull();
        assertThat(pullCallMapper.selectByExecution(executionId).get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.PLANNED.code());
    }

    @Test
    void terminalCallAndMaterialResultsCannotBeOverwrittenByLateWriteBack() {
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        TenantContext.set(7L);
        PullTaskMaterialMember material = materialMapper.selectByExecution(executionId).stream()
                .filter(row -> call.getId().equals(row.getPullCallId()))
                .findFirst().orElseThrow();

        assertThat(pullCallMapper.markSubmitted(
                call.getId(), call.getIdempotencyKey(), 620L)).isEqualTo(1);
        assertThat(pullCallMapper.writeBackResult(call.getId(),
                PullTaskPullCallStatus.WRITTEN_BACK.code(), null, null, 630L)).isEqualTo(1);
        assertThat(pullCallMapper.writeBackResult(call.getId(),
                PullTaskPullCallStatus.UNKNOWN.code(), "LATE", "late", 640L)).isZero();
        assertThat(materialMapper.writeBackPullResult(new PullTaskMaterialPullResult(
                material.getId(), PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success(
                        material.getNormalizedPhone() + "@s.whatsapp.net", 630L),
                630L))).isEqualTo(1);
        assertThat(materialMapper.writeBackPullResult(new PullTaskMaterialPullResult(
                material.getId(), PullTaskMaterialPullStatus.FAILED.code(),
                PullTaskFactResult.reason("LATE", "late"), 640L))).isZero();

        assertThat(pullCallMapper.selectByExecution(executionId).get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(materialMapper.selectByExecution(executionId).stream()
                .filter(row -> material.getId().equals(row.getId()))
                .findFirst().orElseThrow().getPullStatus())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
    }

    @Test
    void unknownParticipantResultsRemainIndependentUnknownFacts() {
        ProtocolAccountRef pullerRef = new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "puller-902", "8613800000902");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L))).thenReturn(List.of(pullerRef));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();
        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        applyBatchResults(call, PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                "PULL_RESULT_UNCONFIRMED", 630L);

        TenantContext.set(7L);
        assertThat(pullCallMapper.selectByExecution(executionId).get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.UNKNOWN.code());
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> call.getId().equals(row.getPullCallId()))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.UNKNOWN.code());
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()))
                .singleElement()
                .extracting(PullTaskGroupAccount::getMembershipStatus)
                .isEqualTo(PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    }

    @Test
    void submittedBatchIsDeferredWithoutReplayingOrInventingUnknownResults() {
        ProtocolAccountRef pullerRef = new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "puller-902", "8613800000902");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(account(911L)));
        when(accountLookup.findActiveProtocolRefs(List.of(902L))).thenReturn(List.of(pullerRef));
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullCall call = service.prepare(candidate, "worker-1", 610L).call();

        assertThat(batchService.prepare(candidate, call, "worker-1", 620L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(pullCallMapper.selectByExecution(executionId).get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());
        PullTaskGroupExecution recoveredCandidate = claim("worker-2", 61_000L, 62_000L);
        PullTaskPullCall recoveredCall = service
                .prepare(recoveredCandidate, "worker-2", 61_010L).call();
        assertThat(recoveredCall.getId()).isEqualTo(call.getId());
        assertThat(recoveredCall.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());

        assertThat(batchService.prepare(
                recoveredCandidate, recoveredCall, "worker-2", 61_020L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        assertThat(pullCallMapper.selectByExecution(executionId).get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());
        assertThat(materialMapper.selectByExecution(executionId))
                .filteredOn(row -> java.util.Objects.equals(
                        call.getId(), row.getPullCallId()))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.SUBMITTED.code());
    }

    private void finishPlannedCall(PullTaskPullCall call, long now) {
        pullCallMapper.markSubmitted(call.getId(), call.getIdempotencyKey(), now);
        pullCallMapper.writeBackResult(call.getId(),
                PullTaskPullCallStatus.WRITTEN_BACK.code(), null, null, now + 1);
        for (PullTaskMaterialMember member : materialMapper.selectByExecution(executionId)) {
            if (call.getId().equals(member.getPullCallId())) {
                materialMapper.writeBackPullResult(new PullTaskMaterialPullResult(
                        member.getId(), PullTaskMaterialPullStatus.SUCCESS.code(),
                        PullTaskFactResult.success(
                                member.getNormalizedPhone() + "@s.whatsapp.net", now + 1),
                        now + 1));
            }
        }
        executionMapper.releaseLock(executionId, "worker-1", now + 1);
    }

    private void applyBatchResults(
            PullTaskPullCall call,
            PullTaskBatchParticipantProtocolOutcome stationOutcome,
            PullTaskBatchParticipantProtocolOutcome materialOutcome,
            String reasonCode,
            long now) {
        TenantContext.set(7L);
        PullTaskPullCall submitted = pullCallMapper.selectByExecution(executionId).stream()
                .filter(row -> row.getId().equals(call.getId()))
                .findFirst().orElseThrow();
        long occurredAt = now;
        for (PullTaskGroupAccount station : groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code())) {
            if (call.getId().equals(station.getPullCallId())) {
                assertThat(applyParticipant(
                        submitted, station.getAccountPhone(), stationOutcome,
                        reasonCode, occurredAt++)).isTrue();
            }
        }
        for (PullTaskMaterialMember material : materialMapper.selectByExecution(executionId)) {
            if (call.getId().equals(material.getPullCallId())) {
                assertThat(applyParticipant(
                        submitted, material.getNormalizedPhone(), materialOutcome,
                        reasonCode, occurredAt++)).isTrue();
            }
        }
    }

    private boolean applyParticipant(
            PullTaskPullCall call,
            String phone,
            PullTaskBatchParticipantProtocolOutcome outcome,
            String reasonCode,
            long occurredAt) {
        return callbackService.handlePullCallParticipant(new PullTaskBatchParticipantCallback(
                7L, 100L, executionId, call.getId(), call.getPullerAccountId(),
                "protocol-" + call.getPullerAccountId(), call.getCommandId(), 1,
                phone + "@s.whatsapp.net", outcome, reasonCode, null,
                outcome == PullTaskBatchParticipantProtocolOutcome.UNKNOWN, occurredAt));
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

    private PullTaskGroupAccount puller() {
        return puller(902L, 1);
    }

    private PullTaskGroupAccount puller(long accountId, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(100L);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("8613800000" + accountId);
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(500L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private PullTaskMaterialMember material(int seq) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(executionId);
        row.setMemberSeq(seq);
        row.setSourceLineNo(seq);
        row.setNormalizedPhone("861390000000" + seq);
        row.setAdminRequired(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static ProtocolAccountRef account(long id) {
        return new ProtocolAccountRef(id, ProtocolBackend.WEB,
                "protocol-" + id, "8613800000" + id);
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
        row.setTotalLineCount(3);
        row.setValidMemberCount(3);
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
            return PullTaskNormalLinkH2Support.dataSource("pull_task_call_planning_test");
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
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml");
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

        @Bean PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullCallMapper pullCallMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskExecutionDispatchProperties dispatchProperties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean PullTaskAccountActionMapper actionMapper() {
            return mock(PullTaskAccountActionMapper.class);
        }

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stationSelectionService(
                PullTaskGroupAccountMapper mapper,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskStationSelectionService(mapper, accountLookup);
        }

        @Bean PullTaskPullCallPlanningResources planningResources(
                PullTaskPullCallMapper pullCallMapper,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskStationSelectionService stationSelectionService,
                PullTaskBatchSizeSelector batchSizeSelector,
                AccountProtocolLookupService accountLookup) {
            return new PullTaskPullCallPlanningResources(
                    pullCallMapper, executionMapper, stationSelectionService,
                    batchSizeSelector, accountLookup);
        }

        @Bean PullTaskPullCallPlanningTransactionService planningService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskPullCallPlanningResources resources) {
            return new PullTaskPullCallPlanningTransactionService(
                    taskMapper, settingMapper, groupAccountMapper, materialMapper, resources);
        }

        @Bean PullTaskBatchAddResources batchAddResources(
                PullTaskGroupExecutionMapper executionMapper,
                AccountProtocolLookupService accountLookup,
                PullTaskPullCallMapper pullCallMapper,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskBatchAddResources(
                    executionMapper, accountLookup, pullCallMapper, outboxService, properties);
        }

        @Bean PullTaskBatchAddTransactionService batchAddService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskBatchAddResources resources) {
            return new PullTaskBatchAddTransactionService(
                    taskMapper, settingMapper, groupAccountMapper, materialMapper, resources);
        }

        @Bean PullTaskUnknownResultResources unknownResultResources(
                PullTaskAccountActionMapper actionMapper,
                PullTaskPullCallMapper pullCallMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper accountMapper) {
            return new PullTaskUnknownResultResources(
                    actionMapper, pullCallMapper, materialMapper, accountMapper);
        }

        @Bean PullTaskProtocolResultCallbackService callbackService(
                PullTaskUnknownResultResources resources,
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskStandardSettingMapper settingMapper) {
            return new PullTaskProtocolResultCallbackServiceImpl(
                    resources, executionMapper, settingMapper);
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
    }
}
