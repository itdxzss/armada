package com.armada.marketing.scheduler;

import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.service.MarketingMessageCommandFactory;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class MarketingRoundWorkerDbTest extends DbTestBase {

    @Autowired
    private MarketingTaskMapper taskMapper;

    @Autowired
    private MarketingTemplateMapper templateMapper;

    @Autowired
    private MarketingTemplateFileMapper fileMapper;

    @Autowired
    private MarketingAccountOccupancyService occupancyService;

    @Autowired
    private AccountGroupMembershipStatusService membershipStatusService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dueRoundGeneratesOneAttemptAndOneOutboxCommandPerTargetInChunks() {
        long now = System.currentTimeMillis();
        Long templateId = insertTemplate("round-1000-" + now);
        Long taskId = insertTask("round-1000-" + now, templateId, 0L, now - 1_000, 30);
        insertTargets(taskId, 1_000);
        List<List<MessageSendCommand>> batches = new ArrayList<>();

        worker(recordingOutbox(batches)).runRound(TEST_TENANT_ID, taskId);

        MarketingTask after = taskMapper.selectTaskById(taskId);
        assertThat(after.getCurrentRoundNo()).isEqualTo(1L);
        assertThat(after.getLastRoundStartedAt()).isGreaterThanOrEqualTo(now);
        assertThat(after.getNextRoundAt()).isGreaterThanOrEqualTo(now + 29_000);
        assertThat(countAttempts(taskId)).isEqualTo(1_000L);
        assertThat(countSubmittedAttempts(taskId, 1L)).isEqualTo(1_000L);

        assertThat(batches).hasSize(2);
        assertThat(batches).extracting(List::size).containsExactly(500, 500);
        List<MessageSendCommand> commands = batches.stream()
                .flatMap(List::stream)
                .toList();
        assertThat(commands).hasSize(1_000);
        assertThat(commands).extracting(command -> command.correlation().marketing().attemptId())
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(commands).extracting(MessageSendCommand::commandId)
                .allSatisfy(commandId -> assertThat(commandId).startsWith("cmd_"))
                .doesNotHaveDuplicates();
        assertThat(commands).extracting(command -> command.payload().type().name())
                .containsOnly("TEXT");
        assertThat(commands).extracting(command -> command.correlation().marketing().roundNo())
                .containsOnly(1L);
    }

    @Test
    void backlogAtThresholdPostponesDueRoundWithoutCreatingAttemptsOrOutbox() {
        long now = System.currentTimeMillis();
        Long templateId = insertTemplate("round-backlog-" + now);
        Long taskId = insertTask("round-backlog-" + now, templateId, 2L, now - 1_000, 30);
        insertTargets(taskId, 1_000);
        List<Long> targetIds = targetIds(taskId);
        insertSubmittedAttempts(taskId, targetIds, 1L, now);
        insertSubmittedAttempts(taskId, targetIds, 2L, now);
        List<List<MessageSendCommand>> batches = new ArrayList<>();

        worker(recordingOutbox(batches)).runRound(TEST_TENANT_ID, taskId);

        MarketingTask after = taskMapper.selectTaskById(taskId);
        assertThat(after.getCurrentRoundNo()).isEqualTo(2L);
        assertThat(after.getNextRoundAt()).isGreaterThan(now);
        assertThat(countAttempts(taskId)).isEqualTo(2_000L);
        assertThat(batches).isEmpty();
    }

    private MarketingRoundWorker worker(MessageSendPort messageSendPort) {
        MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
        properties.setBacklogMultiplier(2);
        properties.setOutboxBatchSize(500);
        MarketingMessageCommandFactory messageFactory = new MarketingMessageCommandFactory(
                templateMapper,
                fileMapper,
                new MarketingMessageComposer());
        return new MarketingRoundWorker(
                taskMapper,
                occupancyService,
                membershipStatusService,
                messageFactory,
                messageSendPort,
                properties,
                Clock.systemUTC());
    }

    private MessageSendPort recordingOutbox(List<List<MessageSendCommand>> batches) {
        MessageSendPort outbox = mock(MessageSendPort.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            batches.add(List.copyOf(commands));
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        }).when(outbox).enqueue(any());
        return outbox;
    }

    private Long insertTemplate(String name) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, image_file_id,
                     content, body_text, buttons, promotion_link, remark,
                     created_at, updated_at, created_by)
                VALUES
                    (?, ?, 2, 'dbtest', NULL, 'hello', NULL, NULL, NULL, NULL, ?, ?, 1)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private Long insertTask(String name, Long templateId, Long currentRoundNo, long nextRoundAt, int intervalSeconds) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, owner_user_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round,
                     send_interval_seconds, is_online_check_enabled,
                     is_abnormal_group_skipped, is_auto_retry_enabled, retry_limit,
                     current_round_no, remark, started_at, next_round_at,
                     last_round_started_at, last_sent_at, finished_at,
                     created_by, created_at, updated_at)
                VALUES
                    (?, 1, ?, 100, 'round-dbtest-group', ?, ?,
                     2, 1000, 1000, 1000, 0, 0, 1, ?, 1, 1, 0, 0,
                     ?, NULL, ?, ?, NULL, NULL, NULL, 1, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, templateId);
            ps.setString(4, name + "-template");
            ps.setInt(5, intervalSeconds);
            ps.setLong(6, currentRoundNo);
            ps.setLong(7, now);
            ps.setLong(8, nextRoundAt);
            ps.setLong(9, now);
            ps.setLong(10, now);
        });
    }

    private void insertTargets(Long taskId, int count) {
        long now = System.currentTimeMillis();
        List<Long> accountIds = insertAccounts(count, now);
        jdbc.batchUpdate("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone,
                     group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count, retry_count,
                     last_attempt_at, last_sent_at, last_reason, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, 0, NULL, NULL, NULL, ?, ?)
                """, targetBatch(taskId, accountIds, now));
    }

    private List<Long> insertAccounts(int count, long now) {
        List<Object[]> accountBatch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String phone = "923000" + String.format("%06d", i);
            accountBatch.add(new Object[] {
                    TEST_TENANT_ID, phone, "WEB", "acc_" + phone, now, now
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO account
                    (tenant_id, owner_user_id, ws_phone, account_type, ownership, protocol_id,
                     protocol_account_id, priority, created_at, updated_at)
                VALUES (?, 1, ?, 1, 1, ?, ?, 0, ?, ?)
                """, accountBatch);
        return jdbc.queryForList("""
                SELECT id
                FROM account
                WHERE tenant_id = ? AND ws_phone LIKE '923000%'
                ORDER BY ws_phone ASC
                """, Long.class, TEST_TENANT_ID);
    }

    private List<Object[]> targetBatch(Long taskId, List<Long> accountIds, long now) {
        List<Object[]> batch = new ArrayList<>(accountIds.size());
        for (int i = 1; i <= accountIds.size(); i++) {
            batch.add(new Object[] {
                    TEST_TENANT_ID,
                    taskId,
                    accountIds.get(i - 1),
                    "923000" + String.format("%06d", i),
                    20_000L + i,
                    "120363" + String.format("%06d", i) + "@g.us",
                    "https://chat.whatsapp.com/dbtest" + i,
                    "round-dbtest-group-" + i,
                    now,
                    now
            });
        }
        return batch;
    }

    private void insertSubmittedAttempts(Long taskId, List<Long> targetIds, long roundNo, long now) {
        jdbc.batchUpdate("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, round_no, attempt_no, is_retry,
                     command_id, status, reason_code, reason_message, message_id,
                     submitted_at, result_at, attempted_at, created_at)
                VALUES
                    (?, ?, ?, ?, 1, 0, ?, 0, NULL, NULL, NULL, ?, NULL, ?, ?)
                """, attemptBatch(taskId, targetIds, roundNo, now));
    }

    private List<Object[]> attemptBatch(Long taskId, List<Long> targetIds, long roundNo, long now) {
        List<Object[]> batch = new ArrayList<>(targetIds.size());
        for (Long targetId : targetIds) {
            batch.add(new Object[] {
                    TEST_TENANT_ID,
                    taskId,
                    targetId,
                    roundNo,
                    "cmd_backlog_" + roundNo + "_" + targetId,
                    now,
                    now,
                    now
            });
        }
        return batch;
    }

    private List<Long> targetIds(Long taskId) {
        return jdbc.queryForList("""
                SELECT id
                FROM marketing_task_target
                WHERE tenant_id = ? AND marketing_task_id = ?
                ORDER BY id ASC
                """, Long.class, TEST_TENANT_ID, taskId);
    }

    private long countAttempts(Long taskId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_send_attempt
                WHERE tenant_id = ? AND marketing_task_id = ?
                """, Long.class, TEST_TENANT_ID, taskId);
        return count == null ? 0L : count;
    }

    private long countSubmittedAttempts(Long taskId, long roundNo) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_task_send_attempt
                WHERE tenant_id = ? AND marketing_task_id = ? AND round_no = ? AND status = 0
                """, Long.class, TEST_TENANT_ID, taskId, roundNo);
        return count == null ? 0L : count;
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
