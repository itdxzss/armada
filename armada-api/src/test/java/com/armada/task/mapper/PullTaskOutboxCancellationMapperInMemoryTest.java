package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 结束单群时取消该范围内未提交发送的普通拉群命令。 */
@SpringJUnitConfig(PullTaskOutboxCancellationMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskOutboxCancellationMapperInMemoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProtocolCommandOutboxMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        com.armada.shared.tenant.TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(dataSource,
                "INSERT INTO pull_task (id, tenant_id, task_type, task_name, mode, status, "
                        + "config_json, created_at, updated_at) VALUES "
                        + "(1, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)",
                execution(11L, 1, 1),
                execution(12L, 2, 2),
                "INSERT INTO pull_task_account_action "
                        + "(id, tenant_id, task_id, group_execution_id, action_type, "
                        + "actor_group_account_id, target_group_account_id, action_status, command_id, "
                        + "created_at, updated_at) VALUES "
                        + "(301, 7, 1, 11, 1, 1, 2, 2, 'cmd-action', 100, 100), "
                        + "(302, 7, 1, 12, 1, 3, 4, 2, 'cmd-other', 100, 100), "
                        + "(303, 7, 1, 11, 2, 1, 2, 2, 'cmd-locked', 100, 100)",
                "INSERT INTO pull_task_pull_call "
                        + "(id, tenant_id, task_id, group_execution_id, call_seq, "
                        + "puller_group_account_id, puller_account_id, planned_material_count, "
                        + "planned_station_count, call_status, command_id, idempotency_key, "
                        + "created_at, updated_at) VALUES "
                        + "(401, 7, 1, 11, 1, 1, 10, 1, 0, 2, 'cmd-call', 'call-401', 100, 100)",
                "INSERT INTO pull_task_material_member "
                        + "(id, tenant_id, group_execution_id, member_seq, source_line_no, "
                        + "normalized_phone, admin_required, pull_status, admin_status, "
                        + "admin_command_id, created_at, updated_at) VALUES "
                        + "(501, 7, 11, 1, 1, '861001', 1, 2, 2, 'cmd-admin', 100, 100)",
                "INSERT INTO pull_task_member_query "
                        + "(id, tenant_id, task_id, group_execution_id, business_key, purpose, "
                        + "command_id, account_id, protocol_account_id, protocol_backend, ws_phone, group_jid, "
                        + "target_jids_json, query_status, attempt_no, requested_at, deadline_at, "
                        + "created_at, updated_at) VALUES "
                        + "(601, 7, 1, 11, 'manager:1', 'MANAGER_JOIN', 'cmd-query', "
                        + "382, 'acc-web', 'WEB', '911', '123@g.us', '[\"456@s.whatsapp.net\"]', "
                        + "1, 1, 100, 1000, 100, 100)",
                "INSERT INTO protocol_command_outbox "
                        + "(tenant_id, command_id, aggregate_type, aggregate_id, status, updated_at) VALUES "
                        + "(7, 'cmd-action', 'PULL_TASK_ACCOUNT_ACTION', 301, 0, 100), "
                        + "(7, 'cmd-other', 'PULL_TASK_ACCOUNT_ACTION', 302, 0, 100), "
                        + "(7, 'cmd-locked', 'PULL_TASK_ACCOUNT_ACTION', 303, 1, 100), "
                        + "(7, 'cmd-dispatching', 'PULL_TASK_ACCOUNT_ACTION', 303, 5, 100), "
                        + "(7, 'cmd-transition', 'PULL_TASK_ACCOUNT_ACTION', 302, 1, 100), "
                        + "(7, 'cmd-call', 'PULL_TASK_PULL_CALL', 401, 0, 100), "
                        + "(7, 'cmd-admin', 'PULL_TASK_MATERIAL_MEMBER', 501, 0, 100), "
                        + "(7, 'cmd-query', 'PULL_TASK_MEMBER_QUERY', 601, 0, 100)");
    }

    @AfterEach
    void tearDown() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void cancelsPendingAndLockedCommandsAndRequestsStopForDispatchingRows() {
        int canceled = mapper.cancelPendingPullTaskCommandsInternal(
                1L, 11L,
                "PULL_TASK_ACCOUNT_ACTION",
                "PULL_TASK_PULL_CALL",
                "PULL_TASK_MATERIAL_MEMBER",
                "PULL_TASK_MEMBER_QUERY",
                java.util.List.of(0, 1), 5, 4, 6, "PULL_TASK_ENDED", 900L);

        assertThat(canceled).isEqualTo(6);
        assertThat(status("cmd-action")).isEqualTo(4);
        assertThat(status("cmd-call")).isEqualTo(4);
        assertThat(status("cmd-admin")).isEqualTo(4);
        assertThat(status("cmd-query")).isEqualTo(4);
        assertThat(status("cmd-other")).isZero();
        assertThat(status("cmd-locked")).isEqualTo(4);
        assertThat(status("cmd-dispatching")).isEqualTo(6);
    }

    @Test
    void sendCommitRequiresMatchingLockContextAndTransitionsLockedToDispatching() {
        jdbc.update("UPDATE protocol_command_outbox SET locked_by=?, locked_at=? WHERE command_id=?",
                "publisher-a", 700L, "cmd-transition");
        ProtocolCommandOutbox stale = lockedRow("cmd-transition", "publisher-a", 701L);
        ProtocolCommandOutbox current = lockedRow("cmd-transition", "publisher-a", 700L);

        assertThat(mapper.markDispatching(java.util.List.of(stale), 800L)).isZero();
        assertThat(mapper.markDispatching(java.util.List.of(current), 800L)).isEqualTo(1);
        assertThat(status("cmd-transition")).isEqualTo(5);
        mapper.markExpiredDispatchingDead(700L, 900L, "outcome unknown", 10);
        assertThat(status("cmd-transition")).isEqualTo(5);
    }

    @Test
    void expiredDispatchingBecomesDeadInsteadOfBeingRetried() {
        jdbc.update("UPDATE protocol_command_outbox SET locked_by=?, locked_at=? WHERE command_id=?",
                "publisher-a", 600L, "cmd-dispatching");

        assertThat(mapper.markExpiredDispatchingDead(700L, 800L, "outcome unknown", 10))
                .isEqualTo(1);
        assertThat(status("cmd-dispatching")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT last_error FROM protocol_command_outbox WHERE command_id=?",
                String.class, "cmd-dispatching")).isEqualTo("outcome unknown");
    }

    @Test
    void failedCancelRequestedDispatchDoesNotReturnToPending() {
        jdbc.update("UPDATE protocol_command_outbox SET locked_by=?, locked_at=? WHERE command_id=?",
                "publisher-a", 600L, "cmd-dispatching");
        ProtocolCommandOutbox row = lockedRow("cmd-dispatching", "publisher-a", 600L);
        jdbc.update("UPDATE protocol_command_outbox SET status=6 WHERE command_id=?", "cmd-dispatching");

        assertThat(mapper.markRetry(row, 1_000L, "send failed", 900L)).isZero();
        assertThat(status("cmd-dispatching")).isEqualTo(4);
    }

    @Test
    void successfulCancelRequestedDispatchCanStillConvergeToSent() {
        jdbc.update("UPDATE protocol_command_outbox "
                        + "SET status=6, locked_by=?, locked_at=? WHERE command_id=?",
                "publisher-a", 600L, "cmd-dispatching");
        ProtocolCommandOutbox row = lockedRow("cmd-dispatching", "publisher-a", 600L);

        assertThat(mapper.markSentBatch(java.util.List.of(row), 900L)).isEqualTo(1);
        assertThat(status("cmd-dispatching")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT sent_at FROM protocol_command_outbox WHERE command_id=?",
                Long.class, "cmd-dispatching")).isEqualTo(900L);
    }

    @Test
    void expiredCancelRequestedDispatchBecomesCanceledWithoutRetry() {
        jdbc.update("UPDATE protocol_command_outbox SET status=6, updated_at=100 WHERE command_id=?",
                "cmd-dispatching");

        assertThat(mapper.markExpiredCancelRequestedCanceled(
                700L, 800L, "task ended", 10)).isEqualTo(1);
        assertThat(status("cmd-dispatching")).isEqualTo(4);
    }

    private int status(String commandId) {
        return jdbc.queryForObject(
                "SELECT status FROM protocol_command_outbox WHERE command_id = ?",
                Integer.class, commandId);
    }

    private static ProtocolCommandOutbox lockedRow(String commandId, String lockedBy, long lockedAt) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId(commandId);
        row.setLockedBy(lockedBy);
        row.setLockedAt(lockedAt);
        return row;
    }

    private static String execution(long id, int seq, int sourceFileIndex) {
        return "INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, "
                + "execution_status, stage, manual_paused, next_run_at, version, "
                + "created_at, updated_at) VALUES (" + id + ", 7, 1, " + seq
                + ", 'chat.whatsapp.com/" + id + "', 'code" + id + "', " + seq
                + ", " + sourceFileIndex + ", '" + id + ".txt', 2, 5, 0, 0, 1, 100, 100)";
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_outbox_cancel_test");
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor,
                    "mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean ProtocolCommandOutboxMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(ProtocolCommandOutboxMapper.class);
        }
    }
}
