package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
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
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.service.impl.PullTaskStandardExecutionLifecycleResources;
import com.armada.task.service.impl.PullTaskStandardExecutionLifecycleServiceImpl;
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

/** LC-02 单群暂停、恢复、结束及资源释放的真实 Mapper 事务测试。 */
@SpringJUnitConfig(PullTaskStandardExecutionLifecycleServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardExecutionLifecycleServiceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PullTaskMapper taskMapper;
    @Autowired private PullTaskStandardExecutionLifecycleService lifecycleService;
    @Autowired private PullTaskGroupBanTerminationService banTerminationService;
    @Autowired private PullTaskExecutionDispatchTrigger dispatchTrigger;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private GroupFolderService groupFolderService;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(dataSource,
                task(1L, 7L, "EXECUTING"),
                task(2L, 7L, "EXECUTING"),
                task(3L, 7L, "COMPLETED"),
                task(4L, 8L, "EXECUTING"),
                task(5L, 7L, "PAUSED"),
                task(6L, 7L, "EXECUTING"),
                execution(11L, 7L, 1L, 1, 2, 4, 0),
                execution(12L, 7L, 1L, 2, 3, 5, 0),
                execution(13L, 7L, 1L, 3, 4, 7, 0),
                execution(21L, 7L, 2L, 1, 2, 5, 1),
                execution(31L, 7L, 3L, 1, 4, 7, 0),
                execution(41L, 8L, 4L, 1, 2, 5, 0),
                execution(51L, 7L, 5L, 1, 2, 5, 1),
                execution(61L, 7L, 6L, 1, 2, 5, 0),
                puller(101L, 7L, 1L, 11L, 501L, null),
                puller(102L, 7L, 1L, 12L, 502L, null),
                puller(201L, 7L, 2L, 21L, 601L, 700L));
        execute("UPDATE pull_task SET creation_mode = 'RESOURCE_POOL' WHERE id IN (2, 5)");
        insertSetting(1L, 18L);
        reset(dispatchTrigger);
        reset(outboxService);
        reset(groupFolderService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pauseOnlyStopsRequestedExecutionAndKeepsItsWaveCheckpoint() throws SQLException {
        insertActiveWave();

        lifecycleService.pause(1L, 11L);

        assertThat(intColumn("manual_paused", "pull_task_group_execution", 11L)).isEqualTo(1);
        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L)).isEqualTo(2);
        assertThat(intColumn("stage", "pull_task_group_execution", 11L)).isEqualTo(4);
        assertThat(stringColumn("lock_owner", 11L)).isNull();
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 12L)).isZero();
        assertThat(longColumn("released_at", "pull_task_group_account", 101L)).isEqualTo(900L);
        assertThat(longColumn("released_at", "pull_task_group_account", 102L)).isNull();
        assertThat(taskMapper.selectLifecycle(1L).getStatus()).isEqualTo("EXECUTING");
        assertThat(intColumn("wave_status", "pull_task_pull_wave", 801L)).isEqualTo(1);
        assertThat(intColumn("next_call_seq", "pull_task_pull_wave", 801L)).isEqualTo(2);
    }

    @Test
    void resumeClearsOnlyGroupPauseAndKeepsLeaseForValidatedRecovery() {
        lifecycleService.resume(2L, 21L);

        assertThat(intColumn("manual_paused", "pull_task_group_execution", 21L)).isZero();
        assertThat(longColumn("released_at", "pull_task_group_account", 201L)).isEqualTo(700L);
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void endCancelsOnlyRequestedExecutionAndLeavesSubmittedFactsForWriteBack()
            throws SQLException {
        insertEndFacts();

        lifecycleService.end(1L, 11L);

        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L)).isEqualTo(6);
        assertThat(intColumn("execution_status", "pull_task_group_execution", 12L)).isEqualTo(3);
        assertThat(intColumn("action_status", "pull_task_account_action", 301L)).isEqualTo(6);
        assertThat(intColumn("action_status", "pull_task_account_action", 302L)).isEqualTo(1);
        assertThat(intColumn("action_status", "pull_task_account_action", 303L)).isEqualTo(2);
        assertThat(intColumn("call_status", "pull_task_pull_call", 401L)).isEqualTo(5);
        assertThat(intColumn("call_status", "pull_task_pull_call", 402L)).isEqualTo(2);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 701L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 702L)).isEqualTo(5);
        assertThat(intColumn("lifecycle_status",
                "pull_task_pull_call_member_attempt", 703L)).isEqualTo(2);
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_material_member", 501L)).isNull();
        assertThat(longColumn("pull_call_id", "pull_task_material_member", 501L)).isNull();
        assertThat(intColumn("membership_status", "pull_task_group_account", 111L)).isZero();
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_group_account", 111L)).isNull();
        assertThat(longColumn("active_pull_attempt_id",
                "pull_task_material_member", 502L)).isEqualTo(703L);
        assertThat(taskMapper.selectLifecycle(1L).getStatus()).isEqualTo("EXECUTING");
        assertThat(intColumn("wave_status", "pull_task_pull_wave", 801L)).isEqualTo(4);
        verify(outboxService).cancelPendingPullTaskCommands(1L, 11L, 900L);
    }

    @Test
    void endingLastGroupCompletesRunningParentButPausedParentWaitsForTaskResume() {
        lifecycleService.end(5L, 51L);
        assertThat(taskMapper.selectLifecycle(5L).getStatus()).isEqualTo("PAUSED");

        lifecycleService.end(1L, 11L);
        lifecycleService.end(1L, 12L);
        PullTask completed = taskMapper.selectLifecycle(1L);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getFinishedAt()).isEqualTo(900L);
    }

    @Test
    void groupBanFailsOnlyMatchingExecutionAndLeavesSiblingAndOtherTenantRunning()
            throws SQLException {
        insertEndFacts();
        execute("UPDATE pull_task_group_execution SET group_link_id = 9011 WHERE id = 41");
        TenantContext.set(99L);

        banTerminationService.terminateBannedGroup(7L, 9011L);

        assertThat(intColumn("execution_status", "pull_task_group_execution", 11L)).isEqualTo(5);
        assertThat(stringColumn("reason_code", 11L)).isEqualTo("GROUP_BANNED");
        assertThat(stringColumn("reason_message", 11L)).isEqualTo("群已被封禁");
        assertThat(longColumn("finished_at", "pull_task_group_execution", 11L)).isEqualTo(900L);
        assertThat(longColumn("next_run_at", "pull_task_group_execution", 11L)).isZero();
        assertThat(stringColumn("lock_owner", 11L)).isNull();
        assertThat(intColumn("execution_status", "pull_task_group_execution", 12L)).isEqualTo(3);
        assertThat(intColumn("execution_status", "pull_task_group_execution", 41L)).isEqualTo(2);
        assertThat(intColumn("action_status", "pull_task_account_action", 301L)).isEqualTo(6);
        assertThat(intColumn("action_status", "pull_task_account_action", 302L)).isEqualTo(1);
        assertThat(longColumn("released_at", "pull_task_group_account", 101L)).isEqualTo(900L);
        assertThat(longColumn("released_at", "pull_task_group_account", 102L)).isNull();
        assertThat(intColumn("wave_status", "pull_task_pull_wave", 801L)).isEqualTo(4);
        Long retryId = jdbc.queryForObject(
                "SELECT id FROM pull_task_group_execution WHERE task_id = 1 AND seq = 1 AND attempt_no = 2",
                Long.class);
        assertThat(intColumn("execution_status", "pull_task_group_execution", retryId)).isEqualTo(1);
        assertThat(intColumn("stage", "pull_task_group_execution", retryId)).isEqualTo(2);
        assertThat(longColumn("group_link_id", "pull_task_group_execution", retryId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_material_member WHERE group_execution_id = ?",
                Integer.class, retryId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT pull_status FROM pull_task_material_member WHERE group_execution_id = ? ORDER BY member_seq",
                Integer.class, retryId)).containsOnly(0);
        assertThat(jdbc.queryForList(
                "SELECT normalized_phone FROM pull_task_material_member WHERE group_execution_id = ? ORDER BY member_seq",
                String.class, retryId)).containsExactly("861001", "861002");
        assertThat(TenantContext.get()).isEqualTo(99L);
        TenantContext.set(7L);
        assertThat(taskMapper.selectLifecycle(1L).getStatus()).isEqualTo("EXECUTING");
        verify(outboxService).cancelPendingPullTaskCommands(1L, 11L, 900L);
        verify(groupFolderService).moveToUngrouped(9011L);
        verify(dispatchTrigger).dispatchAfterCommit();
    }

    @Test
    void repeatedGroupBanIsIdempotentAndKeepsTxtWaitingForAnotherGroup() {
        banTerminationService.terminateBannedGroup(7L, 9021L);
        int version = intColumn("version", "pull_task_group_execution", 21L);
        banTerminationService.terminateBannedGroup(7L, 9021L);

        assertThat(intColumn("execution_status", "pull_task_group_execution", 21L)).isEqualTo(5);
        assertThat(stringColumn("reason_code", 21L)).isEqualTo("GROUP_BANNED");
        assertThat(intColumn("manual_paused", "pull_task_group_execution", 21L)).isZero();
        assertThat(intColumn("version", "pull_task_group_execution", 21L)).isEqualTo(version);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_group_execution WHERE task_id = 2 AND seq = 1 AND attempt_no = 2",
                Integer.class)).isEqualTo(1);
        assertThat(taskMapper.selectLifecycle(2L).getStatus()).isEqualTo("EXECUTING");
        verify(outboxService, times(1)).cancelPendingPullTaskCommands(2L, 21L, 900L);
        verify(groupFolderService, times(1)).moveToUngrouped(9021L);
        verify(dispatchTrigger, times(1)).dispatchAfterCommit();
    }

    @Test
    void pastedManualLinkWithoutSourceFolderDoesNotCreateRetry() {
        banTerminationService.terminateBannedGroup(7L, 9061L);

        assertThat(intColumn("execution_status", "pull_task_group_execution", 61L)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_group_execution WHERE task_id = 6 AND attempt_no = 2",
                Integer.class)).isZero();
        verify(groupFolderService, times(0)).moveToUngrouped(9061L);
    }

    @Test
    void groupBanFailsLastPausedExecutionButKeepsParentPaused() {
        banTerminationService.terminateBannedGroup(7L, 9051L);

        assertThat(intColumn("execution_status", "pull_task_group_execution", 51L)).isEqualTo(5);
        assertThat(stringColumn("reason_code", 51L)).isEqualTo("GROUP_BANNED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pull_task_group_execution WHERE task_id = 5 AND seq = 1 AND attempt_no = 2",
                Integer.class)).isEqualTo(1);
        assertThat(taskMapper.selectLifecycle(5L).getStatus()).isEqualTo("PAUSED");
        verify(groupFolderService).moveToUngrouped(9051L);
    }

    @Test
    void repeatedOperationsAreIdempotentAndOwnershipOrStateViolationsFail() {
        lifecycleService.pause(1L, 11L);
        int version = intColumn("version", "pull_task_group_execution", 11L);
        lifecycleService.pause(1L, 11L);
        assertThat(intColumn("version", "pull_task_group_execution", 11L)).isEqualTo(version);

        assertThatThrownBy(() -> lifecycleService.resume(1L, 13L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> lifecycleService.pause(1L, 41L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> lifecycleService.pause(3L, 31L))
                .isInstanceOf(BusinessException.class);
    }

    private void insertEndFacts() throws SQLException {
        insertActiveWave();
        execute("INSERT INTO pull_task_account_action "
                + "(id, tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, created_at, updated_at) "
                + "VALUES (301, 7, 1, 11, 1, 101, 102, 1, 100, 100), "
                + "(302, 7, 1, 12, 1, 102, 101, 1, 100, 100), "
                + "(303, 7, 1, 11, 2, 101, 102, 2, 100, 100)");
        execute("INSERT INTO pull_task_pull_call "
                + "(id, tenant_id, task_id, group_execution_id, call_seq, puller_group_account_id, "
                + "puller_account_id, planned_material_count, planned_station_count, call_status, "
                + "idempotency_key, created_at, updated_at) VALUES "
                + "(401, 7, 1, 11, 1, 101, 501, 1, 0, 1, 'planned', 100, 100), "
                + "(402, 7, 1, 11, 2, 101, 501, 1, 0, 2, 'submitted', 100, 100)");
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, normalized_phone, "
                + "admin_required, pull_call_id, pull_status, active_pull_attempt_id, admin_status, created_at, updated_at) VALUES "
                + "(501, 7, 11, 1, 1, '861001', 0, 401, 0, 701, 0, 100, 100), "
                + "(502, 7, 11, 2, 2, '861002', 0, 402, 1, 703, 0, 100, 100), "
                + "(503, 7, 12, 1, 1, '861003', 0, NULL, 0, NULL, 0, 100, 100)");
        execute("INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, role_type, "
                + "role_seq, membership_status, active_pull_attempt_id, pull_call_id, "
                + "availability_status, created_at, updated_at) VALUES "
                + "(111, 7, 1, 11, 701, '86701', 3, 1, 0, 702, 401, 1, 100, 100)");
        execute("INSERT INTO pull_task_pull_call_member_attempt "
                + "(id, tenant_id, task_id, group_execution_id, pull_call_id, participant_type, "
                + "participant_ref_id, target_phone, target_jid, puller_group_account_id, "
                + "attempt_no, lifecycle_status, active_slot, submitted_at, created_at, updated_at) VALUES "
                + "(701, 7, 1, 11, 401, 1, 501, '861001', '861001@s.whatsapp.net', 101, 1, 1, 1, NULL, 100, 100), "
                + "(702, 7, 1, 11, 401, 2, 111, '86701', '86701@s.whatsapp.net', 101, 1, 1, 1, NULL, 100, 100), "
                + "(703, 7, 1, 11, 402, 1, 502, '861002', '861002@s.whatsapp.net', 101, 1, 2, 1, 100, 100, 100)");
    }

    private void insertActiveWave() throws SQLException {
        execute("INSERT INTO pull_task_pull_wave "
                + "(id, tenant_id, task_id, group_execution_id, wave_no, wave_type, "
                + "wave_status, planned_call_count, next_call_seq, next_dispatch_at, "
                + "version, created_at, updated_at) "
                + "VALUES (801, 7, 1, 11, 1, 1, 1, 3, 2, 5000, 1, 100, 100)");
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id=801 WHERE id=11");
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
                + "(id, tenant_id, task_id, seq, group_link_id, normalized_link, invite_code, source_link_line_no, "
                + "source_file_index, source_file_name, execution_status, stage, manual_paused, "
                + "next_run_at, lock_owner, lock_expires_at, version, created_at, updated_at) VALUES ("
                + id + ", " + tenantId + ", " + taskId + ", " + seq + ", " + (9000 + id)
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

    private void insertSetting(long taskId, long sourceGroupFolderId) throws SQLException {
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, source_group_folder_id, "
                + "material_admin_timing, pull_count_min, pull_count_max, "
                + "pull_interval_seconds, puller_count_per_group, station_count_per_call, "
                + "concurrent_group_count, manager_group_id, puller_group_id, "
                + "manager_group_name, puller_group_name, created_at, updated_at) VALUES "
                + "(7, " + taskId + ", 0, " + sourceGroupFolderId
                + ", 1, 1, 2, 1, 1, 0, 1, 88, 89, 'manager', 'puller', 100, 100)");
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

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_execution_lifecycle_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
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
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
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

        @Bean PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean
        PullTaskParentCompletionService completionService(
                PullTaskMapper taskMapper, PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskParentCompletionService(taskMapper, executionMapper);
        }

        @Bean
        PullTaskStandardExecutionLifecycleResources lifecycleResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskAccountActionMapper actionMapper,
                com.armada.task.mapper.PullTaskMemberQueryMapper memberQueryMapper,
                PullTaskLifecyclePullResources pull,
                ProtocolCommandOutboxService outboxService) {
            return new PullTaskStandardExecutionLifecycleResources(
                    executionMapper, settingMapper, actionMapper,
                    memberQueryMapper, pull, outboxService);
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
        PullTaskStandardExecutionLifecycleServiceImpl lifecycleService(
                PullTaskMapper taskMapper,
                PullTaskStandardExecutionLifecycleResources resources,
                PullTaskParentCompletionService completionService,
                PullTaskExecutionDispatchTrigger dispatchTrigger,
                GroupFolderService groupFolderService) {
            return new PullTaskStandardExecutionLifecycleServiceImpl(
                    taskMapper, resources, completionService, dispatchTrigger,
                    groupFolderService, () -> 900L);
        }

        @Bean GroupFolderService groupFolderService() {
            return mock(GroupFolderService.class);
        }
    }
}
