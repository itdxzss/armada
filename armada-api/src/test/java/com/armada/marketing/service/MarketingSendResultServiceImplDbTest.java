package com.armada.marketing.service;

import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketingSendResultServiceImplDbTest extends DbTestBase {

    private static final int TARGET_STATUS_SUCCESS = 3;

    @Autowired
    private ProtocolMessageSendResultReportedSink service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void staleCommandResultCannotFinalizeCurrentAttempt() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("stale-command-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363stale@g.us";
        Long attemptId = insertSubmittedAttempt(
                taskId, targetId, groupLinkId, groupJid, 0L, now);

        ProtocolMessageSendResultReportedEvent stale = successEvent(
                taskId,
                targetId,
                attemptId,
                groupJid,
                0L,
                now + 1_000,
                "cmd_stale");
        service.handleSendResultReported(stale);

        Integer status = jdbc.queryForObject(
                "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
                Integer.class,
                attemptId);
        Integer sent = jdbc.queryForObject(
                "SELECT sent_message_count FROM marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        assertThat(status).isZero();
        assertThat(sent).isZero();
    }

    @Test
    void immediateFailureRetriesSameAttemptOnceAndRejectsStaleCommandResult() {
        long now = System.currentTimeMillis();
        RetryFixture fixture = insertRetryFixture(now);

        service.handleSendResultReported(failedEvent(fixture, fixture.firstCommandId(), now + 1_000));

        Map<String, Object> retrying = jdbc.queryForMap("""
                SELECT attempt_no, is_retry, command_id, status
                FROM marketing_task_send_attempt
                WHERE id = ?
                """, fixture.attemptId());
        String retryCommandId = (String) retrying.get("command_id");
        assertThat(retrying.get("attempt_no")).isEqualTo(2);
        assertThat(retrying.get("is_retry")).isEqualTo(true);
        assertThat(retryCommandId).startsWith("cmd_").isNotEqualTo(fixture.firstCommandId());
        assertThat(retrying.get("status")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT retry_count FROM marketing_task_target WHERE id = ?",
                Integer.class,
                fixture.targetId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT failed_message_count FROM marketing_task WHERE id = ?",
                Integer.class,
                fixture.taskId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_command_outbox WHERE command_id = ?",
                Integer.class,
                retryCommandId)).isEqualTo(1);

        service.handleSendResultReported(successEvent(
                fixture.taskId(),
                fixture.targetId(),
                fixture.attemptId(),
                fixture.groupJid(),
                0L,
                now + 2_000,
                fixture.firstCommandId()));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
                Integer.class,
                fixture.attemptId())).isZero();

        service.handleSendResultReported(failedEvent(fixture, retryCommandId, now + 3_000));

        Map<String, Object> finalized = jdbc.queryForMap("""
                SELECT attempt_no, is_retry, command_id, status
                FROM marketing_task_send_attempt
                WHERE id = ?
                """, fixture.attemptId());
        assertThat(finalized.get("attempt_no")).isEqualTo(2);
        assertThat(finalized.get("is_retry")).isEqualTo(true);
        assertThat(finalized.get("command_id")).isEqualTo(retryCommandId);
        assertThat(finalized.get("status")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT failed_message_count FROM marketing_task WHERE id = ?",
                Integer.class,
                fixture.taskId())).isEqualTo(1);
    }

    @Test
    void successResultRollsUpAttemptSnapshotToDynamicTargetDetail() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("send-result-dynamic-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult@g.us";
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, groupJid, 1L, now);

        service.handleSendResultReported(successEvent(taskId, targetId, attemptId, groupJid, 1L, now + 1_000));

        Map<String, Object> target = jdbc.queryForMap("""
                SELECT group_link_id, group_jid, group_link_url, group_name,
                       status, sent_message_count, failed_message_count,
                       last_attempt_at, last_sent_at, last_reason
                FROM marketing_task_target
                WHERE id = ?
                """, targetId);
        assertThat(target.get("group_link_id")).isEqualTo(groupLinkId);
        assertThat(target.get("group_jid")).isEqualTo(groupJid);
        assertThat(target.get("group_link_url"))
                .isEqualTo("https://chat.whatsapp.com/sendresult-" + now);
        assertThat(target.get("group_name")).isEqualTo("发送结果群");
        assertThat(target.get("status")).isEqualTo(TARGET_STATUS_SUCCESS);
        assertThat(target.get("sent_message_count")).isEqualTo(1);
        assertThat(target.get("failed_message_count")).isEqualTo(0);
        assertThat(target.get("last_attempt_at")).isEqualTo(now + 1_000);
        assertThat(target.get("last_sent_at")).isEqualTo(now + 1_000);
        assertThat(target.get("last_reason")).isNull();
    }

    @Test
    void sameGroupSuccessfulAcrossRoundsCountsOnceButMessagesCountTwice() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("same-success-group-" + now, now);
        Long firstTargetId = insertDynamicTarget(taskId, 501L, "923sendresult-a", now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult@g.us";
        Long secondTargetId = insertFixedTarget(
                taskId, 502L, "923sendresult-b", groupLinkId, groupJid, now + 1);
        Long firstAttemptId = insertSubmittedAttempt(taskId, firstTargetId, groupLinkId, groupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(taskId, secondTargetId, groupLinkId, groupJid, 2L, now + 2_000);

        service.handleSendResultReported(successEvent(
                taskId, firstTargetId, firstAttemptId, groupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                taskId, secondTargetId, secondAttemptId, groupJid, 2L, now + 3_000));

        Map<String, Object> task = jdbc.queryForMap("""
                SELECT target_group_count, sent_message_count
                FROM marketing_task
                WHERE id = ?
                """, taskId);
        assertThat(task.get("target_group_count")).isEqualTo(1);
        assertThat(task.get("sent_message_count")).isEqualTo(2);
        Integer factRows = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_success_group
                WHERE marketing_task_id = ? AND group_jid = ?
                """, Integer.class, taskId, groupJid);
        assertThat(factRows).isEqualTo(1);
    }

    @Test
    void differentSuccessfulGroupsIncrementCumulativeCountSeparately() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("different-success-groups-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long firstGroupLinkId = insertGroupLink(now);
        Long secondGroupLinkId = insertGroupLink(now + 1);
        String firstGroupJid = "120363sendresult-a@g.us";
        String secondGroupJid = "120363sendresult-b@g.us";
        Long firstAttemptId = insertSubmittedAttempt(
                taskId, targetId, firstGroupLinkId, firstGroupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(
                taskId, targetId, secondGroupLinkId, secondGroupJid, 2L, now + 2_000);

        service.handleSendResultReported(successEvent(
                taskId, targetId, firstAttemptId, firstGroupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                taskId, targetId, secondAttemptId, secondGroupJid, 2L, now + 3_000));

        Integer groupCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        assertThat(groupCount).isEqualTo(2);
    }

    @Test
    void sameGroupInDifferentTasksCountsOnceForEachTask() {
        long now = System.currentTimeMillis();
        String groupJid = "120363sendresult-shared@g.us";
        Long groupLinkId = insertGroupLink(now);
        Long firstTaskId = insertTask("shared-group-task-a-" + now, now);
        Long secondTaskId = insertTask("shared-group-task-b-" + now, now + 1);
        Long firstTargetId = insertDynamicTarget(firstTaskId, 601L, "923shared-a", now);
        Long secondTargetId = insertDynamicTarget(secondTaskId, 602L, "923shared-b", now + 1);
        Long firstAttemptId = insertSubmittedAttempt(
                firstTaskId, firstTargetId, groupLinkId, groupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(
                secondTaskId, secondTargetId, groupLinkId, groupJid, 1L, now + 1);

        service.handleSendResultReported(successEvent(
                firstTaskId, firstTargetId, firstAttemptId, groupJid, 1L, now + 1_000));
        service.handleSendResultReported(successEvent(
                secondTaskId, secondTargetId, secondAttemptId, groupJid, 1L, now + 1_001));

        Integer firstCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                firstTaskId);
        Integer secondCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                secondTaskId);
        assertThat(firstCount).isEqualTo(1);
        assertThat(secondCount).isEqualTo(1);
    }

    @Test
    void legacyGroupCreationAttemptSuccessDoesNotEnterOrdinaryMarketingGroupCount() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("legacy-group-creation-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult-group-creation@g.us";
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, groupJid, 1L, now);
        Long groupCreationTaskId = insertLegacyGroupCreationTask(taskId, now);
        Long groupCreationItemId = insertLegacyGroupCreationItem(
                groupCreationTaskId, taskId, targetId, attemptId, groupLinkId, groupJid, now);

        service.handleSendResultReported(
                successEvent(taskId, targetId, attemptId, groupJid, 1L, now + 1_000));

        Integer groupCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        Integer factRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_task_success_group WHERE marketing_task_id = ?",
                Integer.class,
                taskId);
        Integer itemStatus = jdbc.queryForObject(
                "SELECT status FROM group_creation_marketing_item WHERE id = ?",
                Integer.class,
                groupCreationItemId);
        assertThat(groupCount).isZero();
        assertThat(factRows).isZero();
        assertThat(itemStatus).isEqualTo(4);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSuccessfulAttemptsForSameGroupIncrementOnlyOnce() throws Exception {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("concurrent-success-group-" + now, now);
        Long firstTargetId = insertDynamicTarget(taskId, 701L, "923concurrent-a", now);
        Long secondTargetId = insertDynamicTarget(taskId, 702L, "923concurrent-b", now + 1);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult-concurrent@g.us";
        Long firstAttemptId = insertSubmittedAttempt(
                taskId, firstTargetId, groupLinkId, groupJid, 1L, now);
        Long secondAttemptId = insertSubmittedAttempt(
                taskId, secondTargetId, groupLinkId, groupJid, 2L, now + 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> {
                awaitStart(ready, start);
                service.handleSendResultReported(successEvent(
                        taskId, firstTargetId, firstAttemptId, groupJid, 1L, now + 1_000));
            });
            Future<?> second = executor.submit(() -> {
                awaitStart(ready, start);
                service.handleSendResultReported(successEvent(
                        taskId, secondTargetId, secondAttemptId, groupJid, 2L, now + 1_001));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            Map<String, Object> task = jdbc.queryForMap("""
                    SELECT target_group_count, sent_message_count
                    FROM marketing_task
                    WHERE id = ?
                    """, taskId);
            Integer factRows = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM marketing_task_success_group
                    WHERE marketing_task_id = ? AND group_jid = ?
                    """, Integer.class, taskId, groupJid);
            assertThat(task.get("target_group_count")).isEqualTo(1);
            assertThat(task.get("sent_message_count")).isEqualTo(2);
            assertThat(factRows).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            jdbc.update("DELETE FROM marketing_task_success_group WHERE marketing_task_id = ?", taskId);
            jdbc.update("DELETE FROM marketing_task_send_attempt WHERE marketing_task_id = ?", taskId);
            jdbc.update("DELETE FROM marketing_task_target WHERE marketing_task_id = ?", taskId);
            jdbc.update("DELETE FROM marketing_task WHERE id = ?", taskId);
            jdbc.update("DELETE FROM group_link WHERE id = ?", groupLinkId);
        }
    }

    @Test
    void softDeletedTaskStillAcceptsLateSuccessfulGroupResult() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("soft-deleted-late-success-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult-soft-deleted@g.us";
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, groupJid, 1L, now);
        jdbc.update("UPDATE marketing_task SET deleted_at = ? WHERE id = ?", now + 500, taskId);

        service.handleSendResultReported(
                successEvent(taskId, targetId, attemptId, groupJid, 1L, now + 1_000));

        Integer attemptStatus = jdbc.queryForObject(
                "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
                Integer.class,
                attemptId);
        Integer groupCount = jdbc.queryForObject(
                "SELECT target_group_count FROM marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        Integer factRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_task_success_group WHERE marketing_task_id = ?",
                Integer.class,
                taskId);
        assertThat(attemptStatus).isEqualTo(1);
        assertThat(groupCount).isEqualTo(1);
        assertThat(factRows).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void missingTaskCounterFailureRollsBackAttemptAndSuccessfulGroupFact() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("missing-task-rollback-" + now, now);
        Long targetId = insertDynamicTarget(taskId, now);
        Long groupLinkId = insertGroupLink(now);
        String groupJid = "120363sendresult-missing-task@g.us";
        Long attemptId = insertSubmittedAttempt(taskId, targetId, groupLinkId, groupJid, 1L, now);
        jdbc.update("DELETE FROM marketing_task WHERE id = ?", taskId);

        try {
            assertThatThrownBy(() -> service.handleSendResultReported(
                    successEvent(taskId, targetId, attemptId, groupJid, 1L, now + 1_000)))
                    .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                    .hasMessageContaining("累计成功群组数量更新失败");

            Integer attemptStatus = jdbc.queryForObject(
                    "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
                    Integer.class,
                    attemptId);
            Integer factRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM marketing_task_success_group WHERE marketing_task_id = ?",
                    Integer.class,
                    taskId);
            assertThat(attemptStatus).isZero();
            assertThat(factRows).isZero();
        } finally {
            jdbc.update("DELETE FROM marketing_task_success_group WHERE marketing_task_id = ?", taskId);
            jdbc.update("DELETE FROM marketing_task_send_attempt WHERE id = ?", attemptId);
            jdbc.update("DELETE FROM marketing_task_target WHERE id = ?", targetId);
            jdbc.update("DELETE FROM group_link WHERE id = ?", groupLinkId);
        }
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

    private RetryFixture insertRetryFixture(long now) {
        Long templateId = insertRetryTemplate(now);
        Long accountGroupId = insertRetryAccountGroup(now);
        Long accountId = insertRetryAccount(accountGroupId, now);
        String groupJid = "120363retry" + now + "@g.us";
        Long groupLinkId = insertRetryGroup(groupJid, now);
        Long taskId = insertTask("immediate-retry-" + now, now);
        jdbc.update("""
                UPDATE marketing_task
                SET account_group_id = ?, marketing_template_id = ?, marketing_template_name = ?,
                    is_auto_retry_enabled = 1, retry_limit = 1,
                    task_start_at = ?, task_end_at = ?, updated_at = ?
                WHERE id = ?
                """, accountGroupId, templateId, "即时重试模板", now - 1_000, now + 60_000, now, taskId);
        Long targetId = insertDynamicTarget(taskId, accountId, "923retry" + now, now);
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     joined_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now, now, now);
        jdbc.update("""
                INSERT INTO marketing_account_occupancy
                    (tenant_id, account_id, marketing_task_id, occupied_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, taskId, now, now, now);
        String firstCommandId = "cmd_retry_first_" + now;
        Long attemptId = insertSubmittedAttempt(
                taskId, targetId, groupLinkId, groupJid, 0L, now, firstCommandId);
        return new RetryFixture(taskId, targetId, attemptId, groupJid, firstCommandId);
    }

    private Long insertRetryTemplate(long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, image_file_id,
                     content, body_text, buttons, promotion_link, mention_all, remark,
                     created_by, created_at, updated_at)
                VALUES
                    (?, ?, 1, 'dbtest', NULL,
                     'hello', NULL, NULL, NULL, 0, NULL,
                     1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "immediate-retry-template-" + now);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertRetryAccountGroup(long now) {
        return insertAndReturnId("""
                INSERT INTO account_group
                    (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "immediate-retry-group-" + now);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertRetryAccount(Long accountGroupId, long now) {
        Long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     protocol_id, protocol_account_id, group_baseline_state,
                     priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 'WEB', ?, 3, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "923retry" + now);
            ps.setLong(3, accountGroupId);
            ps.setString(4, "acc_retry_" + now);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state,
                     risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        return accountId;
    }

    private Long insertRetryGroup(String groupJid, long now) {
        Long groupLinkId = insertGroupLink(now);
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject,
                     announce_only, created_at, updated_at)
                VALUES (?, ?, ?, '即时重试群', 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, now, now);
        return groupLinkId;
    }

    private Long insertDynamicTarget(Long taskId, long now) {
        return insertDynamicTarget(taskId, 501L, "923sendresult", now);
    }

    private Long insertDynamicTarget(Long taskId, Long accountId, String accountPhone, long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     target_scope, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 2, NULL, NULL, NULL, NULL,
                     1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, accountId);
            ps.setString(4, accountPhone);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
    }

    private Long insertFixedTarget(Long taskId,
                                   Long accountId,
                                   String accountPhone,
                                   Long groupLinkId,
                                   String groupJid,
                                   long now) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     target_scope, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 1, ?, ?, NULL, '固定发送群',
                     1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, accountId);
            ps.setString(4, accountPhone);
            ps.setLong(5, groupLinkId);
            ps.setString(6, groupJid);
            ps.setLong(7, now);
            ps.setLong(8, now);
        });
    }

    private Long insertGroupLink(long now) {
        return insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES
                    (?, ?, '发送结果群', 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "https://chat.whatsapp.com/sendresult-" + now);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertLegacyGroupCreationTask(Long marketingTaskId, long now) {
        return insertAndReturnId("""
                INSERT INTO group_creation_marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, marketing_task_id,
                     status, matched_item_count, unmatched_file_count, success_count,
                     failed_count, abandoned_count, send_interval_seconds, created_at, updated_at)
                VALUES
                    (?, ?, 1, '兼容链账号分组', 1, '兼容链模板', ?,
                     2, 1, 0, 0, 0, 0, 30, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "legacy-group-creation-" + now);
            ps.setLong(3, marketingTaskId);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
    }

    private Long insertLegacyGroupCreationItem(Long groupCreationTaskId,
                                               Long marketingTaskId,
                                               Long targetId,
                                               Long attemptId,
                                               Long groupLinkId,
                                               String groupJid,
                                               long now) {
        return insertAndReturnId("""
                INSERT INTO group_creation_marketing_item
                    (tenant_id, task_id, file_index, file_name, material_content, participant_count,
                     account_id, account_phone, protocol_account_id, group_subject, group_jid, group_link_id,
                     marketing_task_id, marketing_target_id, marketing_attempt_id, command_id,
                     status, next_run_at, created_at, updated_at)
                VALUES
                    (?, ?, 0, 'legacy.txt', '8613800138000', 1,
                     501, '923sendresult', 'acc_923sendresult', '兼容链群', ?, ?,
                     ?, ?, ?, ?, 3, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, groupCreationTaskId);
            ps.setString(3, groupJid);
            ps.setLong(4, groupLinkId);
            ps.setLong(5, marketingTaskId);
            ps.setLong(6, targetId);
            ps.setLong(7, attemptId);
            ps.setString(8, "cmd_success_group_" + marketingTaskId + "_1_" + groupJid);
            ps.setLong(9, now);
            ps.setLong(10, now);
        });
    }

    private Long insertSubmittedAttempt(Long taskId,
                                        Long targetId,
                                        Long groupLinkId,
                                        String groupJid,
                                        long roundNo,
                                        long now) {
        return insertSubmittedAttempt(
                taskId,
                targetId,
                groupLinkId,
                groupJid,
                roundNo,
                now,
                commandId(taskId, roundNo, groupJid));
    }

    private Long insertSubmittedAttempt(Long taskId,
                                        Long targetId,
                                        Long groupLinkId,
                                        String groupJid,
                                        long roundNo,
                                        long now,
                                        String commandId) {
        return insertAndReturnId("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                     round_no, attempt_no, is_retry, command_id, status, reason_code,
                     reason_message, message_id, submitted_at, result_at, attempted_at, created_at)
                VALUES
                    (?, ?, ?, ?, ?, '发送结果群',
                     ?, 1, 0, ?, 0, NULL,
                     NULL, NULL, ?, NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, taskId);
            ps.setLong(3, targetId);
            ps.setLong(4, groupLinkId);
            ps.setString(5, groupJid);
            ps.setLong(6, roundNo);
            ps.setString(7, commandId);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.setLong(10, now);
        });
    }

    private static ProtocolMessageSendResultReportedEvent successEvent(Long taskId,
                                                                       Long targetId,
                                                                       Long attemptId,
                                                                       String groupJid,
                                                                       long roundNo,
                                                                       long timestamp) {
        return successEvent(
                taskId,
                targetId,
                attemptId,
                groupJid,
                roundNo,
                timestamp,
                commandId(taskId, roundNo, groupJid));
    }

    private static ProtocolMessageSendResultReportedEvent successEvent(Long taskId,
                                                                       Long targetId,
                                                                       Long attemptId,
                                                                       String groupJid,
                                                                       long roundNo,
                                                                       long timestamp,
                                                                       String commandId) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_success_group_" + attemptId,
                TEST_TENANT_ID,
                taskId,
                targetId,
                attemptId,
                roundNo,
                "acc_923sendresult",
                groupJid,
                commandId,
                true,
                "wamid." + attemptId,
                null,
                null,
                timestamp,
                "worker-a",
                null,
                null,
                "marketing_task",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                timestamp - 1,
                null,
                null);
    }

    private static ProtocolMessageSendResultReportedEvent failedEvent(
            RetryFixture fixture,
            String commandId,
            long timestamp) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_failed_retry_" + timestamp,
                TEST_TENANT_ID,
                fixture.taskId(),
                fixture.targetId(),
                fixture.attemptId(),
                0L,
                "acc_retry",
                fixture.groupJid(),
                commandId,
                false,
                null,
                "SEND_FAILED",
                "rate limited",
                timestamp,
                "worker-a",
                null,
                null,
                "marketing_task",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                timestamp - 1,
                null,
                null);
    }

    private static String commandId(Long taskId, long roundNo, String groupJid) {
        return "cmd_success_group_" + taskId + "_" + roundNo + "_" + groupJid;
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

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发发送结果测试启动超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发发送结果测试被中断", ex);
        }
    }

    private record RetryFixture(
            Long taskId,
            Long targetId,
            Long attemptId,
            String groupJid,
            String firstCommandId
    ) {
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }
}
