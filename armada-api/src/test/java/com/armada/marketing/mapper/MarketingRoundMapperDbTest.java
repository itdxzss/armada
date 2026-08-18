package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.support.MarketingSendAttemptResult;
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
        int first = mapper.markAttemptSuccess(successResult(attempt, now + 5, now + 10));
        int second = mapper.markAttemptSuccess(successResult(attempt, now + 15, now + 20));

        assertThat(attempt.getId()).isNotNull();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(mapper.countUnfinishedAttempts(taskId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT group_jid FROM marketing_task_send_attempt WHERE id = ?",
                String.class,
                attempt.getId())).isEqualTo("120363001@g.us");
    }

    private static MarketingSendAttemptResult successResult(
            MarketingTaskSendAttempt attempt,
            long groupStatusCheckedAt,
            long resultAt) {
        return new MarketingSendAttemptResult(
                attempt.getId(),
                attempt.getCommandId(),
                "wamid.1",
                null,
                null,
                attempt.getGroupJid(),
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                groupStatusCheckedAt,
                resultAt);
    }

    @Test
    void selectDynamicTargetGroupsIncludesBaselineGroups() {
        long accountGroupId = insertAccountGroup("dynamic-baseline");
        long accountId = insertAccount(accountGroupId, "923900000001", 2);
        String baselineJid = "1203630baseline@g.us";
        String joinedAfterImportJid = "1203630joined@g.us";
        long baselineGroupId = insertGroup("baseline", baselineJid);
        long joinedAfterImportGroupId = insertGroup("joined-after-import", joinedAfterImportJid);
        insertBaseline(accountId, "[\"" + baselineJid + "\"]");
        insertMembership(accountId, baselineGroupId, baselineJid);
        insertMembership(accountId, joinedAfterImportGroupId, joinedAfterImportJid);

        List<MarketingTargetCandidateRow> rows = mapper.selectDynamicTargetGroups(null, accountId, null);

        assertThat(rows).extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly(baselineJid, joinedAfterImportJid);
        assertThat(rows).allSatisfy(row -> assertThat(row.getAccountId()).isEqualTo(accountId));
    }

    @Test
    void selectDynamicTargetGroupsAppliesAccountGroupSendAtBoundary() {
        long accountGroupId = insertAccountGroup("dynamic-send-at");
        long accountId = insertAccount(accountGroupId, "923900000004", 2);
        long cutoff = System.currentTimeMillis() - 60_000L;
        String oldJid = "120363old-send-at@g.us";
        String newJid = "120363new-send-at@g.us";
        long oldGroupId = insertGroup("old-send-at", oldJid);
        long newGroupId = insertGroup("new-send-at", newJid);
        insertMembership(accountId, oldGroupId, oldJid, cutoff - 1_000L);
        insertMembership(accountId, newGroupId, newJid, cutoff + 1_000L);

        List<MarketingTargetCandidateRow> rows = mapper.selectDynamicTargetGroups(null, accountId, cutoff);

        assertThat(rows).extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly(newJid);
    }

    @Test
    void selectDynamicTargetGroupsIgnoresAccountAndGroupStatusButExcludesDeletedMembership() {
        long accountGroupId = insertAccountGroup("dynamic-status");
        long accountId = insertAccount(accountGroupId, "923900000005", 2);
        String groupJid = "120363status@g.us";
        long groupId = insertGroup("status", groupJid);
        insertMembership(accountId, groupId, groupJid);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE account_state
                SET login_state = 0, account_state = 8, risk_status = 2, mute_status = 1, updated_at = ?
                WHERE account_id = ?
                """, now, accountId);
        jdbc.update("""
                UPDATE group_link
                SET membership_state = 1, deleted_at = ?, updated_at = ?
                WHERE id = ?
                """, now, now, groupId);
        jdbc.update("""
                UPDATE group_link_health
                SET health_status = 3, is_banned = 1, updated_at = ?
                WHERE group_link_id = ?
                """, now, groupId);

        assertThat(mapper.selectDynamicTargetGroups(null, accountId, null))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly(groupJid);

        jdbc.update("""
                UPDATE account_group_membership
                SET deleted_at = ?, updated_at = ?
                WHERE account_id = ? AND group_jid = ? AND deleted_at IS NULL
                """, now, now, accountId, groupJid);
        jdbc.update("""
                DELETE binding
                FROM wa_account_group_binding binding
                JOIN group_link handle ON handle.group_id = binding.group_id
                WHERE binding.account_id = ? AND handle.id = ?
                """, accountId, groupId);

        assertThat(mapper.selectDynamicTargetGroups(null, accountId, null)).isEmpty();
    }

    @Test
    void selectDynamicTargetGroupsRequiresProtocolAccountId() {
        long accountGroupId = insertAccountGroup("dynamic-routing");
        long accountId = insertAccount(accountGroupId, "923900000006", 2);
        String groupJid = "120363routing@g.us";
        long groupId = insertGroup("routing", groupJid);
        insertMembership(accountId, groupId, groupJid);
        jdbc.update("UPDATE account SET protocol_account_id = NULL WHERE id = ?", accountId);

        assertThat(mapper.selectDynamicTargetGroups(null, accountId, null)).isEmpty();
    }

    @Test
    void selectCurrentTargetGroupReturnsOnlyCurrentMembership() {
        long accountGroupId = insertAccountGroup("fixed-current");
        long accountId = insertAccount(accountGroupId, "923900000002", 2);
        String groupJid = "120363fixed-current@g.us";
        long groupId = insertGroup("fixed-current", groupJid);
        insertMembership(accountId, groupId, groupJid);

        MarketingTargetCandidateRow row = mapper.selectCurrentTargetGroup(accountId, groupId);

        assertThat(row).isNotNull();
        assertThat(row.getAccountId()).isEqualTo(accountId);
        assertThat(row.getGroupLinkId()).isEqualTo(groupId);
        assertThat(row.getGroupJid()).isEqualTo(groupJid);
    }

    @Test
    void selectCurrentTargetGroupReturnsNullWhenAccountNoLongerInGroup() {
        long accountGroupId = insertAccountGroup("fixed-missing");
        long accountId = insertAccount(accountGroupId, "923900000003", 2);
        long groupId = insertGroup("fixed-missing", "120363fixed-missing@g.us");

        MarketingTargetCandidateRow row = mapper.selectCurrentTargetGroup(accountId, groupId);

        assertThat(row).isNull();
    }

    @Test
    void selectCurrentTargetGroupReturnsNullForRetainedExitedMembership() {
        long accountGroupId = insertAccountGroup("fixed-kicked");
        long accountId = insertAccount(accountGroupId, "923900000009", 2);
        String groupJid = "120363fixed-kicked@g.us";
        long groupId = insertGroup("fixed-kicked", groupJid);
        insertMembership(accountId, groupId, groupJid);
        long now = System.currentTimeMillis();
        setCurrentPresence(accountId, groupId, 2, "REMOVED", now);

        MarketingTargetCandidateRow row = mapper.selectCurrentTargetGroup(accountId, groupId);

        assertThat(row).isNull();
    }

    @Test
    void selectCurrentTargetGroupUsesCurrentFactsWhenLegacyMembershipIsStale() {
        long accountGroupId = insertAccountGroup("fixed-current-facts");
        long accountId = insertAccount(accountGroupId, "923900000010", 2);
        String groupJid = "120363fixed-current-facts@g.us";
        long groupId = insertGroup("fixed-current-facts", groupJid);
        insertMembership(accountId, groupId, groupJid);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE account_group_membership
                SET membership_status = 3, status_source = 'STALE_LEGACY', status_updated_at = ?
                WHERE account_id = ? AND group_link_id = ? AND deleted_at IS NULL
                """, now, accountId, groupId);

        MarketingTargetCandidateRow row = mapper.selectCurrentTargetGroup(accountId, groupId);

        assertThat(row).isNotNull();
        assertThat(row.getMembershipStatus()).isEqualTo(1);
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
                     protocol_account_id, group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setString(4, "acc_" + phone);
            ps.setInt(5, baselineState);
            ps.setLong(6, now);
            ps.setLong(7, now);
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
        Long currentGroupId = insertAndReturnId("""
                INSERT INTO wa_group
                    (tenant_id, group_jid, display_name, origin, created_at, updated_at)
                VALUES (?, ?, ?, 5, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, groupJid);
            ps.setString(3, "round-group-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO wa_group_profile
                    (tenant_id, group_id, subject, announce_only, health_status, banned,
                     created_at, updated_at)
                VALUES (?, ?, ?, 0, 1, 0, ?, ?)
                """, TEST_TENANT_ID, currentGroupId, "WA-round-" + suffix, now, now);
        Long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, group_id, link_url, group_name, origin,
                     membership_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, currentGroupId);
            ps.setString(3, "https://chat.whatsapp.com/round-" + suffix);
            ps.setString(4, "round-group-" + suffix);
            ps.setLong(5, now);
            ps.setLong(6, now);
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
        insertMembership(accountId, groupLinkId, groupJid, now);
    }

    private void insertMembership(Long accountId, Long groupLinkId, String groupJid, long joinedAt) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     joined_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, joinedAt, now, now, now);
        Long currentGroupId = jdbc.queryForObject(
                "SELECT group_id FROM group_link WHERE id = ?", Long.class, groupLinkId);
        String phone = jdbc.queryForObject(
                "SELECT ws_phone FROM account WHERE id = ?", String.class, accountId);
        Long participantId = insertAndReturnId("""
                INSERT INTO wa_group_participant
                    (tenant_id, group_id, pn_jid, phone, presence_status, presence_source,
                     presence_observed_at, role, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, 1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, currentGroupId);
            ps.setString(3, phone + "@s.whatsapp.net");
            ps.setString(4, phone);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
        Integer wasInInitialBaseline = jdbc.query("""
                SELECT 1
                FROM account_group_baseline
                WHERE account_id = ?
                  AND JSON_CONTAINS(baseline_group_jids, JSON_QUOTE(?)) = 1
                LIMIT 1
                """, rs -> rs.next() ? 1 : null, accountId, groupJid);
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                    (tenant_id, account_id, group_id, participant_id,
                     was_in_initial_baseline, membership_active_since_at,
                     last_observed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, currentGroupId, participantId,
                wasInInitialBaseline, joinedAt, now, now, now);
    }

    private void setCurrentPresence(Long accountId, Long groupLinkId, int presenceStatus,
                                    String exitType, long observedAt) {
        jdbc.update("""
                UPDATE wa_group_participant participant
                JOIN wa_account_group_binding binding
                  ON binding.tenant_id = participant.tenant_id
                 AND binding.participant_id = participant.id
                JOIN group_link handle
                  ON handle.tenant_id = binding.tenant_id
                 AND handle.group_id = binding.group_id
                SET participant.presence_status = ?,
                    participant.presence_source = 'TEST_FIXTURE',
                    participant.presence_observed_at = ?,
                    participant.last_exit_type = ?,
                    participant.last_exited_at = ?,
                    participant.updated_at = ?
                WHERE binding.account_id = ?
                  AND handle.id = ?
                """, presenceStatus, observedAt, exitType, observedAt, observedAt,
                accountId, groupLinkId);
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
