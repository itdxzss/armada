package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.boot.config.MyBatisConfig;
import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.service.GroupDataPackageAllocationService;
import com.armada.resource.service.GroupDataPackageAllocationService.AllocationRef;
import com.armada.resource.service.GroupDataPackageAllocationService.Settlement;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.GroupDataPackageTaskProjectionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.service.impl.GroupDataPackageTaskProjectionServiceImpl;
import com.armada.task.service.impl.PullTaskDataPackageSourceService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 以真实 Mapper/租户插件/事务验证数据包和现有任务的来源与结果边界。 */
@SpringJUnitConfig(GroupDataPackageTaskProjectionInMemoryTest.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class GroupDataPackageTaskProjectionInMemoryTest {
    @Autowired private DataSource dataSource;
    @Autowired private GroupDataPackageTaskProjectionService projection;
    @Autowired private GroupDataPackageTaskProjectionMapper mapper;
    @Autowired private GroupDataPackageAllocationService allocation;
    @Autowired private PullTaskDataPackageSourceService sourceService;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private DataSourceTransactionManager transactionManager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        org.mockito.Mockito.reset(allocation);
        jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at) "
                + "VALUES(11,7,'task','NORMAL_LINK','EXECUTING','{}',1,1)");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void latestExecutionWinsAndOldCallbacksCannotReplaceItsOutcome() {
        execution(101, 1, 5);
        material(101, 2);
        execution(102, 2, 2);
        material(102, 0);
        projection.synchronize(List.of(81L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.CLAIMED)));
        clearInvocations(allocation);
        jdbc.update("UPDATE pull_task_material_member SET pull_status=3 WHERE group_execution_id=101");
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2 WHERE group_execution_id=102");
        projection.synchronizeTask(11L);
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.SUCCESS)));
    }

    @Test
    void perMemberProjectionIgnoresPreviousGroupAndDoesNotReadOtherMembers() {
        execution(101, 1, 5);
        material(101, 2);
        execution(102, 2, 2);
        material(102, 0);
        long oldId = jdbc.queryForObject("SELECT id FROM pull_task_material_member WHERE group_execution_id=101", Long.class);
        long newId = jdbc.queryForObject("SELECT id FROM pull_task_material_member WHERE group_execution_id=102", Long.class);
        projection.synchronizeMaterialMembers(101L, List.of(oldId));
        verifyNoInteractions(allocation);
        projection.synchronizeMaterialMembers(102L, List.of(newId));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.CLAIMED)));
    }

    @Test
    void unknownHistoryIsHeldAfterNewGroupIsAbandoned() {
        execution(101, 1, 5);
        material(101, 4);
        execution(102, 2, 6);
        material(102, 5);
        projection.synchronize(List.of(81L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.UNKNOWN)));
    }

    @Test
    void onlyConfirmedUnusedAllocationIsReleasedWhenTaskWasDeleted() {
        execution(101, 1, 1);
        material(101, 0);
        jdbc.update("UPDATE pull_task SET status='WAIT_START',deleted_at=100 WHERE id=11");
        projection.synchronizeTask(11L);
        verify(allocation).release(List.of(new AllocationRef(91L, 3L, 11L, 1)));
    }

    @Test
    void privacyRefusalIsNotAnUnregisteredNumberAndHistoryIsTenantScoped() {
        execution(101, 1, 4);
        material(101, 3);
        jdbc.update("UPDATE pull_task_material_member SET pull_reason_code='PRIVACY_BLOCKED',pull_result_at=100");
        projection.synchronize(List.of(81L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.PRIVACY_REJECTED)));
        assertThat(projection.privacyRejectedPhones(List.of("66812345678"), 50)).containsExactly("66812345678");
        assertThat(projection.privacyRejectedPhones(List.of("66812345678"), 101)).isEmpty();
        clearInvocations(allocation);
        TenantContext.set(8L);
        projection.synchronize(List.of(81L));
        assertThat(projection.privacyRejectedPhones(List.of("66812345678"), 0)).isEmpty();
        verifyNoInteractions(allocation);
    }

    @Test
    void endingPendingRetryKeepsExplicitFailureForManualRecovery() {
        execution(101, 1, 6);
        material(101, 5);
        jdbc.update("UPDATE pull_task_material_member SET pull_failure_count=1,pull_reason_code='TIMEOUT'");
        projection.synchronize(List.of(81L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.RETRYABLE_FAILED)));
    }

    @Test
    void pausedTaskPreventsDestructivePackageOperations() {
        execution(101, 1, 3);
        material(101, 0);
        jdbc.update("UPDATE pull_task SET status='PAUSED'");
        assertThatThrownBy(() -> projection.assertNotActivelyUsed(81L))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE pull_task SET status='ENDED'");
        projection.assertNotActivelyUsed(81L);
    }

    @Test
    void claimBindsActualAllocationVersionAndFailureRollsBackPriorBinding() {
        execution(101, 1, 0);
        material(101, 0);
        jdbc.update("UPDATE pull_task_material_member SET source_allocation_version=NULL");
        var request = new GroupDataPackageAllocationService.ClaimRequest(81L, 1, 11L, 1, List.of(91L));
        org.mockito.Mockito.when(allocation.claim(request)).thenReturn(List.of(
                new GroupDataPackageAllocationService.Phone(91L, "66812345678", false, 1, 1, "TH", 4L)));
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(101L); row.setTaskId(11L); row.setSeq(1);
        row.setSourcePackageId(81L); row.setSourcePackageGeneration(1);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(ignored -> sourceService.claim(List.of(row)));
        assertThat(materialMapper.selectByExecution(101L).get(0).getSourceAllocationVersion()).isEqualTo(4L);
        jdbc.update("UPDATE pull_task_material_member SET source_allocation_version=NULL");
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
            sourceService.claim(List.of(row));
            throw new BusinessException(com.armada.shared.exception.ErrorCode.CONFLICT);
        })).isInstanceOf(BusinessException.class);
        assertThat(materialMapper.selectByExecution(101L).get(0).getSourceAllocationVersion()).isNull();
    }

    @Test
    void executionLockSerializesProjectionWithConcurrentGroupReplacement() throws Exception {
        execution(101, 1, 2);
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> {
            TenantContext.set(7L);
            try {
                tx.executeWithoutResult(ignored -> {
                    mapper.lockLatestExecutions(List.of(81L));
                    acquired.countDown();
                    try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                });
            } finally { TenantContext.clear(); }
        });
        assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second = java.util.concurrent.CompletableFuture.runAsync(() -> {
            TenantContext.set(7L);
            try { tx.executeWithoutResult(ignored -> mapper.lockLatestExecutions(List.of(81L))); }
            finally { TenantContext.clear(); }
        });
        try {
            assertThatThrownBy(() -> second.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally { release.countDown(); }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        second.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void taskProjectionDoesNotLockAnotherTaskUsingTheSamePackage() throws Exception {
        execution(101, 1, 2);
        material(101, 2);
        execution(201, 2, 2);
        jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at) "
                + "VALUES(12,7,'other task','NORMAL_LINK','EXECUTING','{}',1,1)");
        jdbc.update("UPDATE pull_task_group_execution SET task_id=12,attempt_no=1 WHERE id=201");
        assertProjectionDoesNotWaitForOtherExecution(201L, () -> projection.synchronizeTask(11L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.SUCCESS)));
    }

    @Test
    void singleExecutionProjectionDoesNotLockAnotherExecutionInTheSameTask() throws Exception {
        execution(101, 1, 2);
        material(101, 2);
        execution(102, 2, 2);
        jdbc.update("UPDATE pull_task_group_execution SET seq=2,source_file_index=2,attempt_no=1 WHERE id=102");
        assertProjectionDoesNotWaitForOtherExecution(102L, () -> projection.synchronizeExecution(101L));
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.SUCCESS)));
    }

    @Test
    void projectionSettlesPackagesInAscendingOrderAcrossExecutionOrder() {
        execution(101, 1, 2);
        material(101, 2);
        execution(102, 2, 2);
        jdbc.update("UPDATE pull_task_group_execution SET seq=2,source_file_index=2,attempt_no=1,source_package_id=80 WHERE id=102");
        material(102, 2);
        jdbc.update("UPDATE pull_task_material_member SET source_package_phone_id=92 WHERE group_execution_id=102");
        projection.synchronizeTask(11L);
        var ordered = org.mockito.Mockito.inOrder(allocation);
        ordered.verify(allocation).settle(List.of(new Settlement(
                new AllocationRef(92L, 3L, 11L, 2), GroupDataPackagePhoneStatus.SUCCESS)));
        ordered.verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.SUCCESS)));
    }

    @Test
    void releasedUnknownAttemptStillPreventsReuseAfterItsExecutionEnds() {
        execution(101, 1, 6);
        material(101, 5);
        releasedAttempt(101L, "UNCERTAIN");
        projection.synchronizeExecution(101L);
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.UNKNOWN)));
    }

    @Test
    void notStartedAttemptCanBeSafelyReleasedAfterItsExecutionEnds() {
        execution(101, 1, 6);
        material(101, 5);
        releasedAttempt(101L, "NOT_STARTED");
        projection.synchronizeExecution(101L);
        verify(allocation).release(List.of(new AllocationRef(91L, 3L, 11L, 1)));
    }

    @Test
    void laterFailureCannotMakeAnEarlierUnknownAttemptResettable() {
        execution(101, 1, 6);
        material(101, 3);
        releasedAttempt(101L, "UNCERTAIN");
        jdbc.update("UPDATE pull_task_material_member SET pull_failure_count=1,pull_reason_code='TIMEOUT'");
        projection.synchronizeExecution(101L);
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.UNKNOWN)));
        clearInvocations(allocation);
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2");
        projection.synchronizeExecution(101L);
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.SUCCESS)));
    }

    @Test
    void previousGroupReleasedUnknownAttemptStillPreventsReuse() {
        execution(101, 1, 5);
        material(101, 0);
        releasedAttempt(101L, "UNCERTAIN");
        execution(102, 2, 6);
        material(102, 5);
        projection.synchronizeExecution(102L);
        verify(allocation).settle(List.of(settlement(GroupDataPackagePhoneStatus.UNKNOWN)));
    }

    private void releasedAttempt(long executionId, String executionState) {
        long memberId = jdbc.queryForObject(
                "SELECT id FROM pull_task_material_member WHERE group_execution_id=?", Long.class, executionId);
        jdbc.update("INSERT INTO pull_task_pull_call_member_attempt(tenant_id,task_id,group_execution_id,"
                + "pull_call_id,participant_type,participant_ref_id,target_phone,attempt_no,lifecycle_status,"
                + "active_slot,protocol_outcome,execution_state,created_at,updated_at) "
                + "VALUES(7,11,?,201,1,?,'66812345678',1,4,NULL,'UNKNOWN',?,1,1)",
                executionId, memberId, executionState);
    }

    private void assertProjectionDoesNotWaitForOtherExecution(long lockedExecutionId, Runnable operation)
            throws Exception {
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        var other = java.util.concurrent.CompletableFuture.runAsync(() -> {
            TenantContext.set(7L);
            try {
                tx.executeWithoutResult(ignored -> {
                    mapper.lockSourceExecution(lockedExecutionId);
                    acquired.countDown();
                    try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                });
            } finally { TenantContext.clear(); }
        });
        assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var current = java.util.concurrent.CompletableFuture.runAsync(() -> {
            TenantContext.set(7L);
            try { operation.run(); }
            finally { TenantContext.clear(); }
        });
        try {
            current.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private Settlement settlement(GroupDataPackagePhoneStatus status) {
        return new Settlement(new AllocationRef(91L, 3L, 11L, 1), status);
    }

    private void execution(long id, int attempt, int state) {
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,attempt_no,"
                + "source_file_name,source_package_id,source_package_generation,execution_status,created_at,updated_at) "
                + "VALUES(?,7,11,1,1,?,'data.txt',81,1,?,1,1)", id, attempt, state);
    }

    private void material(long executionId, int status) {
        jdbc.update("INSERT INTO pull_task_material_member(tenant_id,group_execution_id,member_seq,source_line_no,"
                + "normalized_phone,source_package_phone_id,source_allocation_version,pull_status,created_at,updated_at) "
                + "VALUES(7,?,1,1,'66812345678',91,3,?,1,1)", executionId, status);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return PullTaskNormalLinkH2Support.dataSource("group_data_package_task_projection"); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(ds, interceptor,
                    "mapper/task/GroupDataPackageTaskProjectionMapper.xml", "mapper/task/PullTaskMaterialMemberMapper.xml");
        }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean GroupDataPackageTaskProjectionMapper projectionMapper(SqlSessionTemplate template) { return template.getMapper(GroupDataPackageTaskProjectionMapper.class); }
        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) { return template.getMapper(PullTaskMaterialMemberMapper.class); }
        @Bean GroupDataPackageAllocationService allocation() { return mock(GroupDataPackageAllocationService.class); }
        @Bean GroupDataPackageTaskProjectionService projection(GroupDataPackageTaskProjectionMapper mapper, GroupDataPackageAllocationService allocation) {
            return new GroupDataPackageTaskProjectionServiceImpl(mapper, allocation);
        }
        @Bean PullTaskDataPackageSourceService source(PullTaskMaterialMemberMapper mapper, GroupDataPackageAllocationService allocation, GroupDataPackageTaskProjectionService projection) {
            return new PullTaskDataPackageSourceService(mapper, allocation, projection);
        }
    }
}
