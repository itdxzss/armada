package com.armada.marketing.service;

import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingSendResultServiceImplDbTest extends DbTestBase {

    private static final int TARGET_STATUS_SUCCESS = 3;

    @Autowired
    private ProtocolMessageSendResultReportedSink service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void successResultRollsUpAttemptSnapshotToDynamicTargetDetail() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("send-result-dynamic-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, now);

        service.handleSendResultReported(successEvent(taskId, targetId, attemptId, now + 1_000));

        Map<String, Object> target = jdbc.queryForMap("""
                SELECT group_link_id, group_jid, group_link_url, group_name,
                       status, sent_message_count, failed_message_count,
                       last_attempt_at, last_sent_at, last_reason
                FROM marketing_task_target
                WHERE id = ?
                """, targetId);
        assertThat(target.get("group_link_id")).isEqualTo(groupLinkId);
        assertThat(target.get("group_jid")).isEqualTo("120363sendresult@g.us");
        assertThat(target.get("group_link_url")).isEqualTo("https://chat.whatsapp.com/sendresult");
        assertThat(target.get("group_name")).isEqualTo("发送结果群");
        assertThat(target.get("status")).isEqualTo(TARGET_STATUS_SUCCESS);
        assertThat(target.get("sent_message_count")).isEqualTo(1);
        assertThat(target.get("failed_message_count")).isEqualTo(0);
        assertThat(target.get("last_attempt_at")).isEqualTo(now + 1_000);
        assertThat(target.get("last_sent_at")).isEqualTo(now + 1_000);
        assertThat(target.get("last_reason")).isNull();
    }

    private Long insertTask(String name, long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round,
                     send_interval_seconds, is_online_check_enabled,
                     is_abnormal_group_skipped, is_auto_retry_enabled, retry_limit,
                     current_round_no, remark, started_at, next_round_at,
                     last_round_started_at, last_sent_at, finished_at,
                     created_by, created_at, updated_at)
                VALUES
                    (?, ?, 100, 'send-result-group', 200, 'send-result-template',
                     2, 1, 0, 1, 0, 0, 1, 30, 1, 1, 0, 0,
                     1, NULL, ?, NULL, ?, NULL, NULL, 1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
    }

    private Long insertDynamicTarget(Long taskId, long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     target_scope, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, 501, '923sendresult', 2, NULL, NULL, NULL, NULL,
                     1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertGroupLink(long now) {
        return insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES
                    (?, 'https://chat.whatsapp.com/sendresult', '发送结果群', 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, now);
            ps.setLong(3, now);
        });
    }

    private Long insertSubmittedAttempt(Long taskId, Long targetId, Long groupLinkId, long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                     round_no, attempt_no, is_retry, command_id, status, reason_code,
                     reason_message, message_id, submitted_at, result_at, attempted_at, created_at)
                VALUES
                    (?, ?, ?, ?, '120363sendresult@g.us', '发送结果群',
                     1, 1, 0, 'cmd_send_result_rollup', 0, NULL,
                     NULL, NULL, ?, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, targetId);
            ps.setLong(4, groupLinkId);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
    }

    private static ProtocolMessageSendResultReportedEvent successEvent(Long taskId, Long targetId,
                                                                       Long attemptId, long timestamp) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_send_result_rollup",
                TEST_TENANT_ID,
                taskId,
                targetId,
                attemptId,
                1L,
                "acc_923sendresult",
                "120363sendresult@g.us",
                "cmd_send_result_rollup",
                true,
                "wamid.send-result",
                null,
                null,
                timestamp,
                "worker-a",
                null,
                null,
                "marketing_task");
    }

    private Long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, keys);
        Number key = keys.getKey();
        assertThat(key).as("generated key for " + sql).isNotNull();
        return key.longValue();
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }
}
