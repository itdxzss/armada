package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
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
        attempt.setGroupLinkId(400L);
        attempt.setGroupJid("120363001@g.us");
        attempt.setGroupName("round group");
        attempt.setRoundNo(1L);
        attempt.setAttemptNo(1);
        attempt.setRetry(false);
        attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
        attempt.setCommandId("cmd_attempt_1");
        attempt.setSubmittedAt(now);
        attempt.setAttemptedAt(now);
        attempt.setCreatedAt(now);

        mapper.insertSendAttempts(List.of(attempt));
        int first = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", attempt.getGroupJid(), now + 10);
        int second = mapper.markAttemptSuccess(attempt.getId(), "wamid.1", attempt.getGroupJid(), now + 20);

        assertThat(attempt.getId()).isNotNull();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(mapper.countUnfinishedAttempts(taskId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT group_jid FROM marketing_task_send_attempt WHERE id = ?",
                String.class,
                attempt.getId())).isEqualTo("120363001@g.us");
    }

    @Test
    void selectDynamicTargetGroupsExcludesBaselineGroups() {
        long accountGroupId = insertAccountGroup("dynamic-baseline");
        long accountId = insertAccount(accountGroupId, "923900000001", 2);
        String baselineJid = "1203630baseline@g.us";
        String joinedAfterImportJid = "1203630joined@g.us";
        long baselineGroupId = insertGroup("baseline", baselineJid);
        long joinedAfterImportGroupId = insertGroup("joined-after-import", joinedAfterImportJid);
        insertBaseline(accountId, "[\"" + baselineJid + "\"]");
        insertMembership(accountId, baselineGroupId, baselineJid);
        insertMembership(accountId, joinedAfterImportGroupId, joinedAfterImportJid);

        List<MarketingTargetCandidateRow> rows = mapper.selectDynamicTargetGroups(accountId);

        assertThat(rows).extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly(joinedAfterImportJid);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getAccountId()).isEqualTo(accountId);
            assertThat(row.getGroupLinkId()).isEqualTo(joinedAfterImportGroupId);
        });
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

    private Long insertAccountGroup(String suffix) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "round-account-group-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertAccount(Long accountGroupId, String phone, int baselineState) {
        long now = System.currentTimeMillis();
        Long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setInt(4, baselineState);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        return accountId;
    }

    private Long insertGroup(String suffix, String groupJid) {
        long now = System.currentTimeMillis();
        Long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "https://chat.whatsapp.com/round-" + suffix);
            ps.setString(3, "round-group-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, announce_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "WA-round-" + suffix, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, now, now);
        return groupLinkId;
    }

    private void insertBaseline(Long accountId, String baselineGroupJids) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, ?, JSON_LENGTH(?), ?, ?, ?)
                """, TEST_TENANT_ID, accountId, baselineGroupJids, baselineGroupJids, now, now, now);
    }

    private void insertMembership(Long accountId, Long groupLinkId, String groupJid) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now);
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
