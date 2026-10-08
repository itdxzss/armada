package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
import com.armada.task.model.dto.PullTaskExecutionObservationCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionFilter;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** RD-01 普通群链接列表聚合必须直接读取真实执行、料子和资源事实。 */
@SpringJUnitConfig(PullTaskStandardReadMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardReadMapperInMemoryTest {

    @jakarta.annotation.Resource
    private DataSource dataSource;

    @jakarta.annotation.Resource
    private PullTaskStandardReadMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        seedFacts();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aggregatesExecutionMaterialAndPullerFactsWithoutFakeZeros() {
        PullTaskStandardTaskAggregate row = mapper.selectTaskAggregates(criteria(List.of(100L)))
                .get(0);

        assertThat(row.getTotalGroupCount()).isEqualTo(3);
        assertThat(row.getCompletedGroupCount()).isEqualTo(1);
        assertThat(row.getWaitingGroupCount()).isEqualTo(1);
        assertThat(row.getPullerShortageGroupCount()).isEqualTo(1);
        assertThat(row.getTotalMemberCount()).isEqualTo(5);
        assertThat(row.getSuccessfulMemberCount()).isEqualTo(2);
        assertThat(row.getFailedMemberCount()).isEqualTo(1);
        assertThat(row.getUnknownMemberCount()).isEqualTo(1);
        assertThat(row.getUnconsumedMemberCount()).isEqualTo(1);
        assertThat(row.getAvailablePullerCount()).isEqualTo(1);
        assertThat(row.getLastExecutedAt()).isEqualTo(900L);
    }

    @Test
    void retryableCurrentFactsAndAttemptHistoryDoNotInflateTerminalCounts() throws SQLException {
        execute("UPDATE pull_task_group_execution SET execution_status = 5 "
                + "WHERE id = 12");
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, source_file_index, attempt_no, source_file_name, "
                + "execution_status, stage, manual_paused, created_at, updated_at) VALUES "
                + "(14, 7, 100, 2, 2, 2, 'b.txt', 1, 2, 0, 2, 2)");
        execute("INSERT INTO pull_task_material_member "
                + "(tenant_id, group_execution_id, member_seq, source_line_no, normalized_phone, "
                + "pull_status, pull_failure_count, created_at, updated_at) VALUES "
                + "(7, 14, 1, 1, '863', 0, 0, 2, 2),"
                + "(7, 14, 2, 2, '864', 0, 0, 2, 2)");

        PullTaskStandardTaskAggregate row = mapper.selectTaskAggregates(criteria(List.of(100L)))
                .get(0);

        assertThat(row.getTotalGroupCount()).isEqualTo(3);
        assertThat(row.getFailedGroupCount()).isZero();
        assertThat(row.getWaitingGroupCount()).isEqualTo(1);
        assertThat(row.getTotalMemberCount()).isEqualTo(5);
        assertThat(row.getSuccessfulMemberCount()).isEqualTo(2);
        assertThat(row.getFailedMemberCount()).isZero();
        assertThat(row.getUnknownMemberCount()).isZero();
        assertThat(row.getUnconsumedMemberCount()).isEqualTo(3);
    }

    @Test
    void tenantInterceptorKeepsOtherTenantFactsInvisible() {
        assertThat(mapper.selectTaskAggregates(criteria(List.of(100L, 200L))))
                .extracting(PullTaskStandardTaskAggregate::getTaskId)
                .containsExactly(100L);
    }

    @Test
    void resourceShortageFilterExcludesRecoveredRowsWaitingForConcurrency() throws SQLException {
        execute("UPDATE pull_task_group_execution SET reason_code='EXECUTION_SLOT_UNAVAILABLE' WHERE id=12");
        PullTaskStandardExecutionFilter filter = new PullTaskStandardExecutionFilter(
                100L, null, PullTaskExecutionStatus.WAIT_RESOURCE.code(), null,
                PullTaskWaitResourceType.PULLER.code(), null, null);
        assertThat(mapper.countExecutions(filter)).isZero();
        assertThat(mapper.selectExecutionPage(filter, 0, 10)).isEmpty();
        var query = new com.armada.task.model.dto.PullTaskStandardExecutionQuery();
        query.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        query.setReasonCode(" EXECUTION_SLOT_UNAVAILABLE ");
        assertThat(mapper.countExecutions(query.toFilter(100L))).isEqualTo(1);
        assertThat(mapper.selectExecutionPage(query.toFilter(100L), 0, 10))
                .singleElement().extracting(row -> row.getId()).isEqualTo(12L);
    }

    @Test
    void executionPagePushesAllWorkbenchFiltersIntoSql() {
        PullTaskStandardExecutionFilter filter = new PullTaskStandardExecutionFilter(
                100L, "l2", PullTaskExecutionStatus.WAIT_RESOURCE.code(), 5,
                PullTaskWaitResourceType.PULLER.code(), 0, null);

        assertThat(mapper.countExecutions(filter)).isEqualTo(1);
        assertThat(mapper.selectExecutionPage(filter, 0, 10))
                .singleElement()
                .extracting(row -> row.getId()).isEqualTo(12L);
    }

    @Test
    void executionAggregatesExposeFrozenPlansCurrentResourcesAndMaterialResults() {
        PullTaskStandardExecutionAggregate row = mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(11L)))
                .get(0);

        assertThat(row.getRequiredManagerCount()).isEqualTo(1);
        assertThat(row.getCurrentManagerCount()).isEqualTo(1);
        assertThat(row.getPlannedPullerCount()).isEqualTo(1);
        assertThat(row.getPlannedStationCount()).isEqualTo(3);
        assertThat(row.getCurrentPullerCount()).isEqualTo(1);
        assertThat(row.getSuccessfulMemberCount()).isEqualTo(2);
    }

    @Test
    void cumulativeAssignedPullersRetainHistoryAcrossReleaseRecoveryAndReplacement() throws SQLException {
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isZero();
        execute("INSERT INTO pull_task_group_account "
                + "(tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, membership_status, availability_status, "
                + "occupied_at, created_at, updated_at) VALUES "
                + "(7, 100, 13, 601, '601', 2, 1, 0, 1, 1, 1, 1)");
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isEqualTo(1);
        assertThat(executionAggregate(13L).getCurrentPullerCount()).isZero();

        execute("UPDATE pull_task_group_account SET released_at=2 WHERE account_id=601");
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isEqualTo(1);
        execute("UPDATE pull_task_group_account SET released_at=NULL, occupied_at=3, "
                + "membership_status=2 WHERE account_id=601");
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isEqualTo(1);
        execute("UPDATE pull_task_group_account SET availability_status=4, released_at=4, "
                + "unavailable_reason_code='ACCOUNT_BANNED' WHERE account_id=601");
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isEqualTo(1);

        execute("INSERT INTO pull_task_group_account "
                + "(tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, membership_status, availability_status, "
                + "unavailable_reason_code, occupied_at, released_at, created_at, updated_at) VALUES "
                + "(7, 100, 13, 602, '602', 2, 2, 2, 4, 'PULLER_REPLACED', 1, 4, 1, 4),"
                + "(7, 100, 13, 603, '603', 2, 3, 2, 4, 'ACCOUNT_UNBOUND', 1, 4, 1, 4),"
                + "(7, 100, 13, 604, '604', 2, 4, 3, 3, 'ACCOUNT_OFFLINE', 1, NULL, 1, 4),"
                + "(7, 100, 13, 605, '605', 2, 5, 2, 2, 'RATE_LIMITED', 1, NULL, 1, 4),"
                + "(7, 100, 13, 606, '606', 2, 6, 0, 1, NULL, 1, NULL, 1, 4)");

        // 角色历史自带账号身份；账号表中不存在这些账号也必须保留累计数。
        assertThat(executionAggregate(13L).getCumulativeAssignedPullerCount()).isEqualTo(6);
        assertThat(executionAggregate(13L).getCurrentPullerCount()).isZero();
    }

    @Test
    void cumulativeAssignedPullersExcludeOtherRolesExecutionsAndTenants() throws SQLException {
        execute("INSERT INTO pull_task_group_account "
                + "(tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, membership_status, availability_status, "
                + "occupied_at, created_at, updated_at) VALUES "
                + "(7, 100, 13, 610, '610', 1, 1, 2, 1, NULL, 1, 1),"
                + "(7, 100, 13, 611, '611', 3, 1, 2, 1, NULL, 1, 1),"
                + "(7, 100, 13, 612, '612', 4, 1, 2, 1, NULL, 1, 1),"
                + "(7, 100, 13, 613, '613', 5, 1, 2, 1, NULL, 1, 1),"
                + "(8, 200, 13, 614, '614', 2, 1, 2, 1, 1, 1, 1)");

        assertThat(mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(11L, 12L, 13L, 21L))))
                .extracting(PullTaskStandardExecutionAggregate::getExecutionId,
                        PullTaskStandardExecutionAggregate::getCumulativeAssignedPullerCount)
                .containsExactly(org.assertj.core.api.Assertions.tuple(11L, 1),
                        org.assertj.core.api.Assertions.tuple(12L, 1),
                        org.assertj.core.api.Assertions.tuple(13L, 0));
    }

    private PullTaskStandardExecutionAggregate executionAggregate(long executionId) {
        return mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(executionId))).get(0);
    }

    @Test
    void directLinkExecutionReadsOrdinaryPullersWithoutManagerShortage() throws SQLException {
        execute("UPDATE pull_task SET creation_mode='DIRECT_LINK' WHERE id=100");
        execute("UPDATE pull_task_standard_setting SET required_manager_count=0, "
                + "manager_group_id=NULL, manager_group_name=NULL WHERE task_id=100");
        execute("DELETE FROM pull_task_group_account WHERE task_id=100 AND role_type=1");
        execute("UPDATE pull_task_group_execution SET stage=10, execution_status=2 WHERE id=11");

        PullTaskStandardExecutionFilter filter = new PullTaskStandardExecutionFilter(
                100L, null, PullTaskExecutionStatus.EXECUTING.code(), 10, null, null, null);
        assertThat(mapper.countExecutions(filter)).isEqualTo(1);
        assertThat(mapper.selectExecutionPage(filter, 0, 10)).singleElement()
                .satisfies(row -> assertThat(row.getId()).isEqualTo(11L));
        PullTaskStandardExecutionAggregate resources = mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(11L))).get(0);
        assertThat(resources.getRequiredManagerCount()).isZero();
        assertThat(resources.getCurrentManagerCount()).isZero();
        assertThat(resources.getCurrentPullerCount()).isEqualTo(1);
        assertThat(mapper.selectTaskAggregates(criteria(List.of(100L))).get(0)
                .getManagerShortageGroupCount()).isZero();
    }

    @Test
    void executionProgressSeparatesCurrentPeopleFromSubmittedAttemptHistory() throws SQLException {
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, "
                + "normalized_phone, pull_status, pull_result_at, created_at, updated_at) VALUES "
                + "(901, 7, 13, 2, 2, '901', 0, NULL, 1, 9000),"
                + "(902, 7, 13, 3, 3, '902', 1, NULL, 1, 9000),"
                + "(903, 7, 13, 4, 4, '903', 4, 7000, 1, 9000),"
                + "(904, 7, 13, 5, 5, '904', 2, 5000, 1, 9000),"
                + "(905, 7, 13, 6, 6, '905', 3, 8000, 1, 9000),"
                + "(906, 7, 13, 7, 7, '906', 0, NULL, 1, 9000)");
        execute("INSERT INTO pull_task_pull_call_member_attempt "
                + "(tenant_id, task_id, group_execution_id, pull_call_id, participant_type, "
                + "participant_ref_id, target_phone, attempt_no, lifecycle_status, active_slot, "
                + "protocol_outcome, submitted_at, created_at, updated_at) VALUES "
                + "(7, 100, 13, 501, 1, 901, '901', 1, 4, NULL, 'UNKNOWN', 1000, 1, 1),"
                + "(7, 100, 13, 502, 1, 901, '901', 2, 4, NULL, 'UNKNOWN', 2000, 1, 1),"
                + "(7, 100, 13, 503, 1, 902, '902', 1, 2, 1, NULL, 3000, 1, 1),"
                + "(7, 100, 13, 504, 1, 903, '903', 1, 3, NULL, 'UNKNOWN', 3000, 1, 1),"
                + "(7, 100, 13, 505, 1, 904, '904', 1, 3, NULL, 'SUCCESS', 4000, 1, 1),"
                + "(7, 100, 13, 506, 1, 905, '905', 1, 3, NULL, 'FAILED', 4000, 1, 1),"
                + "(7, 100, 13, 507, 1, 906, '906', 1, 1, 1, NULL, NULL, 1, 1),"
                + "(7, 100, 13, 501, 2, 901, '901', 1, 3, NULL, 'UNKNOWN', 1000, 1, 1),"
                + "(8, 200, 13, 508, 1, 901, '901', 1, 3, NULL, 'UNKNOWN', 1000, 1, 1)");

        PullTaskStandardExecutionAggregate row = mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(13L, 21L))).get(0);

        assertThat(row.getExecutionId()).isEqualTo(13L);
        assertThat(row.getTotalMemberCount()).isEqualTo(7);
        assertThat(row.getUnconsumedMemberCount()).isEqualTo(3);
        assertThat(row.getRetryPendingCount()).isEqualTo(1);
        assertThat(row.getSubmittedMemberCount()).isEqualTo(1);
        assertThat(row.getUnknownMemberCount()).isEqualTo(1);
        assertThat(row.getSubmittedAttemptCount()).isEqualTo(6L);
        assertThat(row.getUnconfirmedAttemptCount()).isEqualTo(3L);
        assertThat(row.getLastSuccessfulAt()).isEqualTo(5000L);
        assertThat(mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(21L)))).isEmpty();
    }

    @Test
    void executionWithoutSubmittedAttemptsDoesNotInventProgress() {
        PullTaskStandardExecutionAggregate row = mapper.selectExecutionAggregates(
                PullTaskStandardExecutionAggregateCriteria.fromEnums(List.of(13L))).get(0);

        assertThat(row.getRetryPendingCount()).isZero();
        assertThat(row.getSubmittedAttemptCount()).isZero();
        assertThat(row.getUnconfirmedAttemptCount()).isZero();
        assertThat(row.getLastSuccessfulAt()).isNull();
    }

    private PullTaskStandardAggregateCriteria criteria(List<Long> taskIds) {
        return PullTaskStandardAggregateCriteria.fromEnums(taskIds);
    }

    @Test
    void observationUsesOnlyActiveWaveAndPendingCallWithoutChangingFacts() throws SQLException {
        execute("INSERT INTO pull_task_pull_wave "
                + "(id, tenant_id, task_id, group_execution_id, wave_no, wave_type, wave_status, "
                + "planned_call_count, next_call_seq, next_dispatch_at, created_at, updated_at) VALUES "
                + "(700, 7, 100, 13, 3, 1, 1, 3, 2, 9000, 1000, 2000)");
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id = 700 WHERE id = 13");
        execute("INSERT INTO pull_task_pull_call "
                + "(id, tenant_id, task_id, group_execution_id, pull_wave_id, call_seq, wave_call_seq, "
                + "planned_material_count, planned_station_count, call_status, idempotency_key, "
                + "submitted_at, created_at, updated_at) VALUES "
                + "(701, 7, 100, 13, 700, 10, 1, 1, 0, 3, 'done', 3000, 1, 1),"
                + "(702, 7, 100, 13, 700, 11, 2, 13, 0, 1, 'next', NULL, 1, 1),"
                + "(703, 7, 100, 13, 700, 12, 3, 1, 0, 1, 'later', NULL, 1, 1),"
                + "(704, 8, 200, 13, 700, 13, 0, 999, 0, 2, 'other-tenant', 1, 1, 1)");

        var observations = mapper.selectExecutionObservations(
                PullTaskExecutionObservationCriteria.fromEnums(
                        List.of(13L, 21L)));

        assertThat(observations).hasSize(1);
        var row = observations.get(0);
        assertThat(row.getExecutionId()).isEqualTo(13L);
        assertThat(row.getWaveNo()).isEqualTo(3);
        assertThat(row.getCallId()).isEqualTo(702L);
        assertThat(row.getWaveCallSeq()).isEqualTo(2);
        assertThat(row.getNextDispatchAt()).isEqualTo(9000L);
        assertThat(row.getPlannedMaterialCount()).isEqualTo(13);
        assertThat(row.getBoundMaterialCount()).isZero();
        assertThat(mapper.selectExecutionObservations(
                PullTaskExecutionObservationCriteria.fromEnums(
                        List.of(21L)))).isEmpty();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT call_status FROM pull_task_pull_call WHERE id = 702")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        execute("UPDATE pull_task_pull_call SET call_status = 2, submitted_at = 6000 WHERE id = 703");
        var submitted = mapper.selectExecutionObservations(
                PullTaskExecutionObservationCriteria.fromEnums(List.of(13L))).get(0);
        assertThat(submitted.getCallId()).isEqualTo(703L);
        assertThat(submitted.getSubmittedAt()).isEqualTo(6000L);
        execute("UPDATE pull_task_pull_wave SET wave_status = 3 WHERE id = 700");
        var closed = mapper.selectExecutionObservations(
                PullTaskExecutionObservationCriteria.fromEnums(List.of(13L))).get(0);
        assertThat(closed.getWaveId()).isNull();
        assertThat(closed.getCallId()).isNull();
    }

    @Test
    void observationActionWaitExcludesFinishedAndOtherTenantActions() throws SQLException {
        execute("INSERT INTO pull_task_account_action "
                + "(tenant_id, task_id, group_execution_id, action_type, actor_group_account_id, "
                + "target_group_account_id, action_status, submitted_at, created_at, updated_at) VALUES "
                + "(7, 100, 13, 1, 1, 2, 3, 1000, 1, 1),"
                + "(7, 100, 13, 1, 1, 3, 2, 3000, 1, 1),"
                + "(8, 200, 13, 1, 1, 4, 2, 2000, 1, 1),"
                + "(7, 100, 13, 7, 1, 5, 2, 500, 1, 1)");
        var row = mapper.selectExecutionObservations(
                PullTaskExecutionObservationCriteria.fromEnums(List.of(13L))).get(0);
        assertThat(row.getActionSubmittedAt()).isEqualTo(3000L);
        assertThat(row.getTaskStatus()).isEqualTo("EXECUTING");
        assertThat(row.getWaveId()).isNull();
    }

    private void seedFacts() throws SQLException {
        execute("INSERT INTO pull_task (id, tenant_id, task_type, task_name, mode, status, "
                + "config_json, created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 't1', 'NORMAL_LINK', 'EXECUTING', '{}', 1, 1),"
                + "(200, 8, 'STANDARD', 't2', 'NORMAL_LINK', 'EXECUTING', '{}', 1, 1)");
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, execution_status, "
                + "stage, manual_paused, wait_resource_type, last_business_executed_at, "
                + "created_at, updated_at) VALUES "
                + "(11, 7, 100, 1, 'l1', 'i1', 1, 1, 'a.txt', 4, 7, 0, NULL, 800, 1, 1),"
                + "(12, 7, 100, 2, 'l2', 'i2', 2, 2, 'b.txt', 3, 5, 0, 2, 900, 1, 1),"
                + "(13, 7, 100, 3, 'l3', 'i3', 3, 3, 'c.txt', 2, 5, 0, NULL, 700, 1, 1),"
                + "(21, 8, 200, 1, 'l4', 'i4', 1, 1, 'd.txt', 4, 7, 0, NULL, 950, 1, 1)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, material_admin_timing, pull_count_min, pull_count_max, "
                + "pull_interval_seconds, puller_count_per_group, station_count_per_call, "
                + "concurrent_group_count, required_manager_count, manager_group_id, "
                + "puller_group_id, station_group_id, manager_group_name, puller_group_name, "
                + "station_group_name, created_at, updated_at) VALUES "
                + "(7, 100, 1, 1, 2, 1, 1, 3, 1, 1, 1, 2, 3, 'm', 'p', 's', 1, 1),"
                + "(8, 200, 1, 1, 2, 1, 1, 3, 1, 1, 1, 2, 3, 'm', 'p', 's', 1, 1)");
        execute("INSERT INTO pull_task_material_member "
                + "(tenant_id, group_execution_id, member_seq, source_line_no, normalized_phone, "
                + "pull_status, pull_failure_count, created_at, updated_at) VALUES "
                + "(7, 11, 1, 1, '861', 2, 0, 1, 1),"
                + "(7, 11, 2, 2, '862', 2, 0, 1, 1),"
                + "(7, 12, 1, 1, '863', 3, 4, 1, 1),"
                + "(7, 12, 2, 2, '864', 4, 0, 1, 1),"
                + "(7, 13, 1, 1, '865', 0, 3, 1, 1),"
                + "(8, 21, 1, 1, '866', 2, 0, 1, 1)");
        execute("INSERT INTO pull_task_pull_call_member_attempt "
                + "(tenant_id, task_id, group_execution_id, pull_call_id, participant_type, "
                + "participant_ref_id, target_phone, puller_group_account_id, attempt_no, "
                + "failure_count_before, lifecycle_status, active_slot, created_at, updated_at) VALUES "
                + "(7, 100, 12, 401, 1, 9001, '863', 5001, 1, 0, 3, NULL, 1, 1),"
                + "(7, 100, 12, 402, 1, 9001, '863', 5002, 2, 1, 3, NULL, 1, 1),"
                + "(7, 100, 12, 403, 1, 9001, '863', 5003, 3, 2, 3, NULL, 1, 1)");
        execute("INSERT INTO pull_task_group_account "
                + "(tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, membership_status, availability_status, "
                + "admin_status, occupied_at, created_at, updated_at) VALUES "
                + "(7, 100, 11, 500, '500', 1, 1, 2, 1, 3, NULL, 1, 1),"
                + "(7, 100, 11, 501, '501', 2, 1, 2, 1, 0, 1, 1, 1),"
                + "(7, 100, 12, 502, '502', 2, 1, 2, 3, 0, 1, 1, 1),"
                + "(8, 200, 21, 503, '503', 2, 1, 2, 1, 0, 1, 1, 1)");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_read_test");
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskStandardReadMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskStandardReadMapper pullTaskStandardReadMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskStandardReadMapper.class);
        }
    }
}
