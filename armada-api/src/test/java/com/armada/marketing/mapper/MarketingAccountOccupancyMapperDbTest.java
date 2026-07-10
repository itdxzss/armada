package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 普通营销账号当前占用唯一闸门真库测试。
 */
class MarketingAccountOccupancyMapperDbTest extends DbTestBase {

    @Autowired
    private MarketingAccountOccupancyMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameAccountCanHaveOnlyOneCurrentTaskAndCanBeReusedAfterRelease() {
        long now = System.currentTimeMillis();
        long accountGroupId = insertAccountGroup("occupancy-unique-" + now);
        long accountId = insertAccount(accountGroupId, "923800" + String.valueOf(now).substring(6));
        long firstTaskId = insertTask("先执行任务-" + now, accountGroupId, now + 600_000L);
        long secondTaskId = insertTask("后执行任务-" + now, accountGroupId, now + 900_000L);
        insertDynamicTarget(firstTaskId, accountId);
        insertDynamicTarget(secondTaskId, accountId);

        assertThat(mapper.insertAvailableTaskAccounts(firstTaskId, now)).isEqualTo(1);
        assertThat(mapper.insertAvailableTaskAccounts(secondTaskId, now + 1L)).isZero();
        assertThat(mapper.selectOwnersByTaskAccounts(secondTaskId)).singleElement().satisfies(owner -> {
            assertThat(owner.getAccountId()).isEqualTo(accountId);
            assertThat(owner.getMarketingTaskId()).isEqualTo(firstTaskId);
            assertThat(owner.getTaskName()).startsWith("先执行任务-");
            assertThat(owner.getTaskEndAt()).isEqualTo(now + 600_000L);
        });

        assertThat(mapper.releaseByTaskId(firstTaskId)).isEqualTo(1);
        assertThat(mapper.insertAvailableTaskAccounts(secondTaskId, now + 2L)).isEqualTo(1);
        assertThat(mapper.selectOwnersByTaskAccounts(secondTaskId))
                .extracting(MarketingAccountOccupancyOwnerRow::getMarketingTaskId)
                .containsExactly(secondTaskId);
    }

    @Test
    void staleCleanupReleasesAccountWhenOwnerTaskIsNoLongerSending() {
        long now = System.currentTimeMillis();
        long accountGroupId = insertAccountGroup("occupancy-stale-" + now);
        long accountId = insertAccount(accountGroupId, "923801" + String.valueOf(now).substring(6));
        long taskId = insertTask("停止任务-" + now, accountGroupId, now + 600_000L);
        insertDynamicTarget(taskId, accountId);
        mapper.insertAvailableTaskAccounts(taskId, now);
        jdbc.update("UPDATE marketing_task SET status = 5 WHERE id = ?", taskId);

        int deleted = mapper.deleteStale(now + 1L);

        assertThat(deleted).isEqualTo(1);
        assertThat(occupancyCount(taskId)).isZero();
    }

    private long insertAccountGroup(String name) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private long insertAccount(long accountGroupId, String phone) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     protocol_account_id, group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, 3, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setString(4, "acc_" + phone);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
    }

    private long insertTask(String taskName, long accountGroupId, long taskEndAt) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round, send_interval_seconds,
                     is_online_check_enabled, is_abnormal_group_skipped, is_auto_retry_enabled, retry_limit,
                     current_round_no, task_start_at, task_end_at, started_at, next_round_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, '占用测试分组', 1, '占用测试模板', 2,
                        1, 0, 1, 0, 0, 1, 30, 1, 1, 0, 0,
                        0, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, taskName);
            ps.setLong(3, accountGroupId);
            ps.setLong(4, now);
            ps.setLong(5, taskEndAt);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setLong(9, now);
        });
    }

    private void insertDynamicTarget(long taskId, long accountId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO marketing_task_target
                    (tenant_id, marketing_task_id, account_id, account_phone, target_scope,
                     status, sent_message_count, failed_message_count, retry_count, created_at, updated_at)
                VALUES (?, ?, ?, '923800000000', 2, 1, 0, 0, 0, ?, ?)
                """, TEST_TENANT_ID, taskId, accountId, now, now);
    }

    private int occupancyCount(long taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_account_occupancy WHERE marketing_task_id = ?",
                Integer.class,
                taskId);
        return count == null ? 0 : count;
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
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
