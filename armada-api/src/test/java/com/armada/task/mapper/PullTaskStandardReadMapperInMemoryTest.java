package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
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
    void executionPagePushesAllWorkbenchFiltersIntoSql() {
        PullTaskStandardExecutionFilter filter = new PullTaskStandardExecutionFilter(
                100L, "l2", PullTaskExecutionStatus.WAIT_RESOURCE.code(), 5,
                PullTaskWaitResourceType.PULLER.code(), 0);

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

    private PullTaskStandardAggregateCriteria criteria(List<Long> taskIds) {
        return PullTaskStandardAggregateCriteria.fromEnums(taskIds);
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
