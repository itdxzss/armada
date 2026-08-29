package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.scheduler.MarketingRoundSchedulerProperties;
import com.armada.marketing.scheduler.MarketingRoundWorker;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 账号全量群回报触发新群即时营销并继续跟随正常轮次的真库验收。 */
class AccountDynamicNewGroupImmediateMarketingDbTest extends DbTestBase {

    @Autowired
    private AccountGroupMembershipReportService reportService;

    @Autowired
    private ProtocolMessageSendResultReportedSink sendResultSink;

    @Autowired
    private MarketingTaskMapper taskMapper;

    @Autowired
    private MarketingTemplateMapper templateMapper;

    @Autowired
    private MarketingTemplateFileMapper templateFileMapper;

    @Autowired
    private MarketingAccountOccupancyService occupancyService;

    @Autowired
    private AccountGroupMembershipStatusService membershipStatusService;

    @Autowired
    private MessageSendPort messageSendPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reportedNewGroupIsSentImmediatelyOnceThenJoinsNormalRound() {
        long now = System.currentTimeMillis();
        AccountFixture account = insertAccount("flow-" + now, 2, now);
        String oldGroupJid = "120363old" + now + "@g.us";
        String newGroupJid = "120363new" + now + "@g.us";
        reportGroups(account, List.of(group(oldGroupJid, "旧群")), "old-" + now);
        jdbc.update("""
                UPDATE account_group_membership
                SET joined_at = ?
                WHERE account_id = ? AND group_jid = ? AND deleted_at IS NULL
                """, now - 10_000, account.accountId(), oldGroupJid);
        MarketingFixture marketing = insertSendingDynamicTask(account, now, now);
        long originalNextRoundAt = marketing.nextRoundAt();

        reportGroups(account, List.of(
                group(oldGroupJid, "旧群"),
                group(newGroupJid, "新群")), "new-first-" + now);
        reportGroups(account, List.of(
                group(oldGroupJid, "旧群"),
                group(newGroupJid, "新群")), "new-duplicate-" + now);

        assertThat(countAttempts(marketing.taskId(), 0L, newGroupJid)).isEqualTo(1);
        assertThat(countOutboxForRound(marketing.taskId(), 0L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT next_round_at FROM marketing_task WHERE id = ?",
                Long.class,
                marketing.taskId())).isEqualTo(originalNextRoundAt);
        assertThat(countAttempts(marketing.taskId(), 0L, oldGroupJid)).isZero();

        completeImmediateAttempt(marketing, newGroupJid, now + 1_000);
        roundWorker().runRound(TEST_TENANT_ID, marketing.taskId());

        assertThat(countAttempts(marketing.taskId(), 1L, newGroupJid)).isEqualTo(1);
        assertThat(countAttempts(marketing.taskId(), 1L, oldGroupJid)).isZero();
    }

    @Test
    void pendingBaselineFirstReportDoesNotTriggerImmediateMarketing() {
        long now = System.currentTimeMillis();
        AccountFixture account = insertAccount("pending-" + now, 1, now);
        MarketingFixture marketing = insertSendingDynamicTask(account, now, now);

        reportGroups(account, List.of(
                group("120363pendinga" + now + "@g.us", "基线群A"),
                group("120363pendingb" + now + "@g.us", "基线群B")), "pending-first-" + now);

        assertThat(countAttemptsForRound(marketing.taskId(), 0L)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT group_baseline_state FROM account WHERE id = ?",
                Integer.class,
                account.accountId())).isEqualTo(2);
    }

    @Test
    void sameGroupReportedByTwoAccountsTriggersEachOwnedTask() {
        long now = System.currentTimeMillis();
        String sharedGroupJid = "120363shared" + now + "@g.us";
        AccountFixture firstAccount = insertAccount("shared-a-" + now, 2, now);
        AccountFixture secondAccount = insertAccount("shared-b-" + now, 2, now + 1);
        MarketingFixture firstMarketing = insertSendingDynamicTask(firstAccount, now, now);
        MarketingFixture secondMarketing = insertSendingDynamicTask(secondAccount, now, now + 1);

        reportGroups(firstAccount, List.of(group(sharedGroupJid, "共享群")), "shared-a-" + now);
        reportGroups(secondAccount, List.of(group(sharedGroupJid, "共享群")), "shared-b-" + now);

        assertThat(countAttempts(firstMarketing.taskId(), 0L, sharedGroupJid)).isEqualTo(1);
        assertThat(countAttempts(secondMarketing.taskId(), 0L, sharedGroupJid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_send_attempt
                WHERE round_no = 0 AND group_jid = ?
                  AND marketing_task_id IN (?, ?)
                """, Integer.class, sharedGroupJid, firstMarketing.taskId(), secondMarketing.taskId()))
                .isEqualTo(2);
    }

    private MarketingRoundWorker roundWorker() {
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);
        return new MarketingRoundWorker(
                taskMapper,
                occupancyService,
                membershipStatusService,
                new MarketingMessageCommandFactory(
                        templateMapper, templateFileMapper, new MarketingMessageComposer()),
                messageSendPort,
                properties,
                Clock.systemUTC());
    }

    private void completeImmediateAttempt(MarketingFixture marketing,
                                          String groupJid,
                                          long resultAt) {
        Map<String, Object> attempt = jdbc.queryForMap("""
                SELECT id, command_id
                FROM marketing_task_send_attempt
                WHERE marketing_task_id = ? AND round_no = 0 AND group_jid = ?
                """, marketing.taskId(), groupJid);
        Long attemptId = ((Number) attempt.get("id")).longValue();
        String commandId = (String) attempt.get("command_id");
        sendResultSink.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "evt-immediate-success-" + attemptId,
                TEST_TENANT_ID,
                marketing.taskId(),
                marketing.targetId(),
                attemptId,
                0L,
                marketing.protocolAccountId(),
                groupJid,
                commandId,
                true,
                "wamid." + attemptId,
                null,
                null,
                resultAt,
                "worker-dbtest",
                null,
                null,
                "marketing_task",
                "NORMAL",
                "GROUP_SEND_ALLOWED",
                resultAt - 1,
                null,
                null,
                null,
                null,
                null));
    }

    private void reportGroups(AccountFixture account,
                              List<AccountGroupsReportedEvent.Group> groups,
                              String eventId) {
        reportService.applyGroupsReported(new AccountGroupsReportedEvent(
                TEST_TENANT_ID,
                account.accountId(),
                account.protocolAccountId(),
                System.currentTimeMillis(),
                groups,
                eventId,
                "dbtest"));
    }

    private AccountFixture insertAccount(String suffix, int baselineState, long now) {
        Long accountGroupId = insertAndReturnId("""
                INSERT INTO account_group
                    (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "immediate-account-group-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
        String phone = "923" + Math.abs(suffix.hashCode()) + Math.abs(now % 10_000);
        String protocolAccountId = "acc_" + phone;
        Long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     protocol_id, protocol_account_id, group_baseline_state,
                     priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 'WEB', ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setString(4, protocolAccountId);
            ps.setInt(5, baselineState);
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state,
                     risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        if (baselineState == 2) {
            jdbc.update("""
                    INSERT INTO account_group_baseline
                        (tenant_id, account_id, baseline_group_jids, group_count,
                         captured_at, created_at, updated_at)
                    VALUES (?, ?, '[]', 0, ?, ?, ?)
                    """, TEST_TENANT_ID, accountId, now, now, now);
        }
        return new AccountFixture(accountGroupId, accountId, phone, protocolAccountId);
    }

    private MarketingFixture insertSendingDynamicTask(AccountFixture account,
                                                       long accountGroupSendAt,
                                                       long now) {
        Long templateId = insertTemplate("task-" + account.accountId(), now);
        long nextRoundAt = now - 1;
        Long taskId = insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round,
                     account_group_send_interval_ms, send_interval_seconds,
                     is_online_check_enabled, is_abnormal_group_skipped,
                     is_auto_retry_enabled, retry_limit, current_round_no, remark,
                     account_group_send_at, task_start_at, task_end_at, started_at,
                     next_round_at, last_round_started_at, last_sent_at, finished_at,
                     created_by, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, 2,
                     1, 0, 1, 0, 0, 1,
                     100, 1200, 1, 1,
                     1, 1, 0, NULL,
                     ?, ?, ?, ?,
                     ?, NULL, NULL, NULL,
                     1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "immediate-task-" + account.accountId());
            ps.setLong(3, account.accountGroupId());
            ps.setString(4, "即时营销账号组");
            ps.setLong(5, templateId);
            ps.setString(6, "即时营销模板");
            ps.setLong(7, accountGroupSendAt);
            ps.setLong(8, now - 1_000);
            ps.setLong(9, now + 120_000);
            ps.setLong(10, now - 1_000);
            ps.setLong(11, nextRoundAt);
            ps.setLong(12, now);
            ps.setLong(13, now);
        });
        Long targetId = insertAndReturnId("""
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
            ps.setLong(3, account.accountId());
            ps.setString(4, account.phone());
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
        jdbc.update("""
                INSERT INTO marketing_account_occupancy
                    (tenant_id, account_id, marketing_task_id, occupied_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, account.accountId(), taskId, now, now, now);
        return new MarketingFixture(
                taskId, targetId, account.accountId(), account.protocolAccountId(), nextRoundAt);
    }

    private Long insertTemplate(String suffix, long now) {
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
            ps.setString(2, "immediate-template-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private int countAttempts(Long taskId, long roundNo, String groupJid) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_send_attempt
                WHERE marketing_task_id = ? AND round_no = ? AND group_jid = ?
                """, Integer.class, taskId, roundNo, groupJid);
    }

    private int countAttemptsForRound(Long taskId, long roundNo) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_send_attempt
                WHERE marketing_task_id = ? AND round_no = ?
                """, Integer.class, taskId, roundNo);
    }

    private int countOutboxForRound(Long taskId, long roundNo) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM protocol_command_outbox o
                JOIN marketing_task_send_attempt a ON a.command_id = o.command_id
                WHERE a.marketing_task_id = ? AND a.round_no = ?
                """, Integer.class, taskId, roundNo);
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

    private static AccountGroupsReportedEvent.Group group(String groupJid, String subject) {
        return new AccountGroupsReportedEvent.Group(
                groupJid, subject, 10, null, null, true, false, null);
    }

    private record AccountFixture(
            Long accountGroupId,
            Long accountId,
            String phone,
            String protocolAccountId
    ) {
    }

    private record MarketingFixture(
            Long taskId,
            Long targetId,
            Long accountId,
            String protocolAccountId,
            long nextRoundAt
    ) {
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }
}
