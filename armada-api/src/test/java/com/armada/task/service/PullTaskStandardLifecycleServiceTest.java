package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
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
import com.armada.task.model.entity.PullTask;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.service.impl.PullTaskStandardLifecycleResources;
import com.armada.task.service.impl.PullTaskStandardLifecycleServiceImpl;
import com.armada.task.service.impl.PullTaskLifecyclePullResources;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** LC-01 任务级暂停、恢复、结束的真实 Mapper 事务测试。 */
@SpringJUnitConfig(PullTaskStandardLifecycleServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardLifecycleServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskMapper taskMapper;
    @Autowired private PullTaskStandardLifecycleService lifecycleService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;
    @Autowired private ProtocolCommandOutboxService outboxService;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(dataSource,
                task(1L, 7L, "EXECUTING"),
                task(2L, 7L, "PAUSED"),
                task(3L, 7L, "COMPLETED"),
                task(4L, 8L, "EXECUTING"),
                task(5L, 7L, "PAUSED"),
                execution(11L, 7L, 1L, 1, 2, 4, 0),
                execution(12L, 7L, 1L, 2, 3, 5, 0),
                execution(13L, 7L, 1L, 3, 4, 7, 0),
                execution(21L, 7L, 2L, 1, 2, 4, 1),
                execution(22L, 7L, 2L, 2, 3, 5, 1),
                execution(51L, 7L, 5L, 1, 6, 5, 0),
                puller(101L, 7L, 1L, 11L, 501L, null),
                puller(102L, 7L, 1L, 12L, 502L, null),
                puller(201L, 7L, 2L, 21L, 601L, 700L));
        reset(dispatchTrigger);
        reset(outboxService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pauseKeepsActiveWaveAndCheckpointWhileBlockingDispatch() throws SQLException {
        insertActiveWave(801L, 1L, 11L);

        lifecycleService.pause(1L);

        PullTask task = taskMapper.selectLifecycle(1L);
        assertThat(task.getStatus()).isEqualTo("PAUSED");
        assertThat(task.getBlockingReason()).isEqualTo("人工暂停");
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(intColumn("manual_paused", 11L)).isZero();
        assertThat(intColumn("manual_paused", 12L)).isZero();
        assertThat(intColumn("stage", 11L)).isEqualTo(4);
        assertThat(intColumn("execution_status", 12L)).isEqualTo(3);
        assertThat(stringColumn("lock_owner", 11L)).isNull();
        assertThat(intColumn("manual_paused", 13L)).isZero();
        assertThat(longColumn("released_at", "pull_task_group_account", 101L))
                .isEqualTo(900L);
        assertThat(longColumn("released_at", "pull_task_group_account", 102L))
                .isEqualTo(900L);
        assertThat(intColumn("wave_status", "pull_task_pull_wave", 801L)).isEqualTo(1);
        assertThat(intColumn("next_call_seq", "pull_task_pull_wave", 801L)).isEqualTo(2);
        assertThat(longColumn("next_dispatch_at", "pull_task_pull_wave", 801L))
                .isEqualTo(5_000L);
    }

    @Test
    void resumePreservesGroupPauseAndLetsValidatedRecoveryReoccupyResources() {
        lifecycleService.resume(2L);

        PullTask task = taskMapper.selectLifecycle(2L);
        assertThat(task.getStatus()).isEqualTo("EXECUTING");
        assertThat(task.getBlockingReason()).isNull();
        assertThat(intColumn("manual_paused", 21L)).isEqualTo(1);
        assertThat(intColumn("manual_paused", 22L)).isEqualTo(1);
        assertThat(longColumn("released_at", "pull_task_group_account", 201L))
                .isEqualTo(700L);
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void endAbandonsRowsAndCancelsCommandsNotYetPublished() throws SQLException {
        insertEndFacts();
        when(outboxService.cancelPendingPullTaskCommands(1L, null, 900L))
                .thenAnswer(ignored -> {
                    assertThat(intColumn("execution_status", 11L)).isEqualTo(6);
                    assertThat(intColumn("execution_status", 12L)).isEqualTo(6);
                    return jdbc.update(
                            "UPDATE protocol_command_outbox "
                                    + "SET status=CASE WHEN status=5 THEN 6 ELSE 4 END, updated_at=900 "
                                    + "WHERE status IN (0, 1, 5)");
                });

        lifecycleService.end(1L);

        PullTask task = taskMapper.selectLifecycle(1L);
        assertThat(task.getStatus()).isEqualTo("ENDED");
        assertThat(task.getBlockingReason()).isEqualTo("人工结束");
        assertThat(task.getFinishedAt()).isEqualTo(900L);
        assertThat(intColumn("execution_status", 11L)).isEqualTo(6);
        assertThat(intColumn("execution_status", 12L)).isEqualTo(6);
        assertThat(intColumn("execution_status", 13L)).isEqualTo(4);
        assertThat(intColumn("action_status", "pull_task_account_action", 301L)).isEqualTo(6);
        assertThat(intColumn("action_status", "pull_task_account_action", 302L)).isEqualTo(5);
        assertThat(intColumn("call_status", "pull_task_pull_call", 401L)).isEqualTo(5);
        assertThat(intColumn("call_status", "pull_task_pull_call", 402L)).isEqualTo(5);
        assertThat(intColumn("pull_status", "pull_task_material_member", 501L)).isEqualTo(5);
        assertThat(intColumn("pull_status", "pull_task_material_member", 502L)).isEqualTo(5);
        assertThat(intColumn("pull_status", "pull_task_material_member", 503L)).isEqualTo(5);
        assertThat(intColumn("pull_status", "pull_task_material_member", 506L)).isEqualTo(1);
        assertThat(intColumn("admin_status", "pull_task_material_member", 504L)).isEqualTo(6);
        assertThat(intColumn("admin_status", "pull_task_material_member", 505L)).isEqualTo(6);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 701L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 702L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 703L)).isEqualTo(2);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 704L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 705L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 706L)).isEqualTo(2);
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_material_member", 502L)).isNull();
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_material_member", 503L)).isNull();
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_material_member", 506L)).isEqualTo(703L);
        assertThat(intColumn("membership_status", "pull_task_group_account", 111L)).isZero();
        assertThat(intColumn("membership_status", "pull_task_group_account", 112L)).isZero();
        assertThat(intColumn("membership_status", "pull_task_group_account", 113L)).isEqualTo(1);
        assertThat(intColumn("wave_status", "pull_task_pull_wave", 801L)).isEqualTo(4);
        verify(outboxService).cancelPendingPullTaskCommands(1L, null, 900L);
    }

    @Test
    void repeatedTargetOperationsAreIdempotentAndIllegalOrCrossTenantOperationsFail() {
        lifecycleService.pause(1L);
        lifecycleService.pause(1L);
        assertThat(taskMapper.selectLifecycle(1L).getVersion()).isEqualTo(2);

        assertThatThrownBy(() -> lifecycleService.resume(3L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> lifecycleService.pause(4L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resumeCompletesParentWhenAllGroupsBecameTerminalDuringTaskPause() {
        lifecycleService.resume(5L);

        PullTask task = taskMapper.selectLifecycle(5L);
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getFinishedAt()).isEqualTo(900L);
    }

    private void insertEndFacts() throws SQLException {
        insertActiveWave(801L, 1L, 11L);
        execute("INSERT INTO pull_task_account_action "
                + "(id, tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, command_id, "
                + "created_at, updated_at) "
                + "VALUES (301, 7, 1, 11, 1, 101, 102, 1, NULL, 100, 100), "
                + "(302, 7, 1, 11, 2, 101, 102, 2, 'cmd-action', 100, 100)");
        execute("INSERT INTO pull_task_pull_call "
                + "(id, tenant_id, task_id, group_execution_id, call_seq, puller_group_account_id, "
                + "puller_account_id, planned_material_count, planned_station_count, call_status, "
                + "command_id, idempotency_key, created_at, updated_at) VALUES "
                + "(401, 7, 1, 11, 1, 101, 501, 1, 0, 1, NULL, 'planned', 100, 100), "
                + "(402, 7, 1, 11, 2, 101, 501, 1, 0, 2, 'cmd-call', 'submitted', 100, 100), "
                + "(403, 7, 1, 11, 3, 101, 501, 1, 0, 2, 'cmd-published', 'published', 100, 100)");
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, normalized_phone, "
                + "admin_required, pull_call_id, pull_status, active_pull_attempt_id, admin_status, admin_command_id, "
                + "created_at, updated_at) VALUES "
                + "(501, 7, 11, 1, 1, '861001', 0, NULL, 0, NULL, 0, NULL, 100, 100), "
                + "(502, 7, 11, 2, 2, '861002', 0, 401, 0, 701, 0, NULL, 100, 100), "
                + "(503, 7, 11, 3, 3, '861003', 0, 402, 1, 702, 0, NULL, 100, 100), "
                + "(504, 7, 11, 4, 4, '861004', 1, NULL, 2, NULL, 1, NULL, 100, 100), "
                + "(505, 7, 11, 5, 5, '861005', 1, NULL, 2, NULL, 2, 'cmd-admin', 100, 100), "
                + "(506, 7, 11, 6, 6, '861006', 0, 403, 1, 703, 0, NULL, 100, 100)");
        execute("INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, role_type, "
                + "role_seq, membership_status, active_pull_attempt_id, pull_call_id, "
                + "availability_status, created_at, updated_at) VALUES "
                + "(111, 7, 1, 11, 701, '86701', 3, 1, 0, 704, 401, 1, 100, 100), "
                + "(112, 7, 1, 11, 702, '86702', 3, 2, 1, 705, 402, 1, 100, 100), "
                + "(113, 7, 1, 11, 703, '86703', 3, 3, 1, 706, 403, 1, 100, 100)");
        execute("INSERT INTO pull_task_pull_call_member_attempt "
                + "(id, tenant_id, task_id, group_execution_id, pull_call_id, participant_type, "
                + "participant_ref_id, target_phone, target_jid, puller_group_account_id, "
                + "attempt_no, lifecycle_status, active_slot, submitted_at, created_at, updated_at) VALUES "
                + "(701, 7, 1, 11, 401, 1, 502, '861002', '861002@s.whatsapp.net', 101, 1, 1, 1, NULL, 100, 100), "
                + "(702, 7, 1, 11, 402, 1, 503, '861003', '861003@s.whatsapp.net', 101, 1, 2, 1, 100, 100, 100), "
                + "(703, 7, 1, 11, 403, 1, 506, '861006', '861006@s.whatsapp.net', 101, 1, 2, 1, 100, 100, 100), "
                + "(704, 7, 1, 11, 401, 2, 111, '86701', '86701@s.whatsapp.net', 101, 1, 1, 1, NULL, 100, 100), "
                + "(705, 7, 1, 11, 402, 2, 112, '86702', '86702@s.whatsapp.net', 101, 1, 2, 1, 100, 100, 100), "
                + "(706, 7, 1, 11, 403, 2, 113, '86703', '86703@s.whatsapp.net', 101, 1, 2, 1, 100, 100, 100)");
        execute("INSERT INTO protocol_command_outbox "
                + "(tenant_id, command_id, aggregate_type, aggregate_id, status, updated_at) VALUES "
                + "(7, 'cmd-action', 'PULL_TASK_ACCOUNT_ACTION', 302, 5, 100), "
                + "(7, 'cmd-call', 'PULL_TASK_PULL_CALL', 402, 0, 100), "
                + "(7, 'cmd-published', 'PULL_TASK_PULL_CALL', 403, 2, 100), "
                + "(7, 'cmd-admin', 'PULL_TASK_MATERIAL_MEMBER', 505, 0, 100)");
    }

    private void insertActiveWave(long id, long taskId, long executionId) throws SQLException {
        execute("INSERT INTO pull_task_pull_wave "
                + "(id, tenant_id, task_id, group_execution_id, wave_no, wave_type, "
                + "wave_status, planned_call_count, next_call_seq, next_dispatch_at, "
                + "version, created_at, updated_at) VALUES ("
                + id + ", 7, " + taskId + ", " + executionId
                + ", 1, 1, 1, 3, 2, 5000, 1, 100, 100)");
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id=" + id
                + " WHERE id=" + executionId);
    }

    private int intColumn(String column, long executionId) {
        return intColumn(column, "pull_task_group_execution", executionId);
    }

    private int intColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private Long longColumn(String column, String table, long id) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private String stringColumn(String column, long executionId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM pull_task_group_execution WHERE id = ?",
                String.class, executionId);
    }

    private static String task(long id, long tenantId, String status) {
        return "INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, version, "
                + "config_json, created_at, updated_at) VALUES (" + id + ", " + tenantId
                + ", 'STANDARD', 'task', 'NORMAL_LINK', '" + status
                + "', 1, '{}', 100, 100)";
    }

    private static String execution(long id, long tenantId, long taskId, int seq,
                                    int status, int stage, int paused) {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, source_link_line_no, "
                + "source_file_index, source_file_name, execution_status, stage, manual_paused, "
                + "next_run_at, lock_owner, lock_expires_at, version, created_at, updated_at) VALUES ("
                + id + ", " + tenantId + ", " + taskId + ", " + seq
                + ", 'chat.whatsapp.com/" + taskId + "-" + seq + "', 'code" + seq
                + "', " + seq + ", " + seq + ", 'a.txt', " + status + ", " + stage + ", "
                + paused + ", 0, 'worker', 5000, 1, 100, 100)";
    }

    private static String puller(long id, long tenantId, long taskId, long executionId,
                                 long accountId, Long releasedAt) {
        String released = releasedAt == null ? "NULL" : releasedAt.toString();
        return "INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, role_type, "
                + "role_seq, membership_status, availability_status, occupied_at, released_at, "
                + "created_at, updated_at) VALUES (" + id + ", " + tenantId + ", " + taskId
                + ", " + executionId + ", " + accountId + ", '861" + accountId
                + "', 2, 1, 2, 1, 100, " + released + ", 100, 100)";
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
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_lifecycle_test");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
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
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean PullTaskPullCallMapper pullCallMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean
        PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean
        ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean
        PullTaskStandardLifecycleResources lifecycleResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskAccountActionMapper actionMapper,
                com.armada.task.mapper.PullTaskMemberQueryMapper memberQueryMapper,
                PullTaskLifecyclePullResources pull,
                ProtocolCommandOutboxService outboxService,
                PullTaskExecutionDispatchTrigger dispatchTrigger) {
            return new PullTaskStandardLifecycleResources(
                    executionMapper, actionMapper, memberQueryMapper, pull,
                    outboxService, dispatchTrigger);
        }

        @Bean
        com.armada.task.mapper.PullTaskMemberQueryMapper memberQueryMapper() {
            return mock(com.armada.task.mapper.PullTaskMemberQueryMapper.class);
        }

        @Bean
        PullTaskLifecyclePullResources lifecyclePullResources(
                PullTaskGroupAccountMapper accountMapper,
                PullTaskPullCallMapper pullCallMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskPullWaveMapper waveMapper) {
            return new PullTaskLifecyclePullResources(
                    accountMapper, pullCallMapper, attemptMapper, materialMapper, waveMapper);
        }

        @Bean
        PullTaskParentCompletionService completionService(
                PullTaskMapper taskMapper, PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskParentCompletionService(taskMapper, executionMapper);
        }

        @Bean
        PullTaskStandardLifecycleService lifecycleService(
                PullTaskMapper taskMapper,
                PullTaskStandardLifecycleResources resources,
                PullTaskParentCompletionService completionService) {
            return new PullTaskStandardLifecycleServiceImpl(
                    taskMapper, resources, completionService, () -> 900L);
        }
    }
}
