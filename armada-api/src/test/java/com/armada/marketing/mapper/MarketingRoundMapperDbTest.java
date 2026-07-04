package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingRoundMapperDbTest extends DbTestBase {

    @Autowired
    private MarketingTaskMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void selectDueSendingTasksOnlyReturnsDueSendingRows() {
        long now = System.currentTimeMillis();
        Long due = insertTask("due", 2, now - 1_000);
        insertTask("future", 2, now + 60_000);
        insertTask("stopped", 5, now - 1_000);

        List<MarketingTask> rows = mapper.selectDueSendingTasks(now, 10);

        assertThat(rows).extracting(MarketingTask::getId).contains(due);
        assertThat(rows).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(2));
    }

    @Test
    void claimDueRoundMovesNextRoundAndIncrementsRound() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("claim", 2, now - 1_000);

        int claimed = mapper.claimDueRound(taskId, now, now + 30_000);
        MarketingTask after = mapper.selectTaskById(taskId);

        assertThat(claimed).isEqualTo(1);
        assertThat(after.getCurrentRoundNo()).isEqualTo(1L);
        assertThat(after.getNextRoundAt()).isEqualTo(now + 30_000);
        assertThat(after.getLastRoundStartedAt()).isEqualTo(now);
    }

    @Test
    void insertAttemptsAndApplyResultAreIdempotentByAttemptId() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("attempt", 2, now - 1_000);
        Long targetId = insertTarget(taskId, "120363001@g.us");
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setMarketingTaskId(taskId);
        attempt.setTargetId(targetId);
        attempt.setRoundNo(1L);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setCommandId("cmd_attempt_1");
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);

        mapper.insertSendAttempts(List.of(attempt));
        int first = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", now + 10);
        int second = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", now + 20);

        assertThat(attempt.getId()).isNotNull();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(mapper.countUnfinishedAttempts(taskId)).isZero();
    }

    private Long insertTask(String suffix, int status, long nextRoundAt) {
        long now = System.currentTimeMillis();
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
                    (?, ?, 100, 'round-group', 200, 'round-template', ?,
                     1, 1, 1, 0, 0, 1, 30, 1, 1, 0, 0, 0, NULL,
                     ?, ?, NULL, NULL, NULL, 1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "round-task-" + suffix);
            ps.setInt(3, status);
            ps.setLong(4, now);
            ps.setLong(5, nextRoundAt);
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
    }

    private Long insertTarget(Long taskId, String groupJid) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, 300, '923000000000', 400, ?, 'https://chat.whatsapp.com/round',
                     'round group', 1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setString(3, groupJid);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
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
