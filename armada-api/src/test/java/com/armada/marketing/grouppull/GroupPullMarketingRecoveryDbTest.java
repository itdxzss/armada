package com.armada.marketing.grouppull;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 拉群营销短租约接管及阶段结果幂等真库测试。 */
class GroupPullMarketingRecoveryDbTest extends DbTestBase {

    @Autowired
    private GroupPullMarketingMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void expiredExecutionLeaseCanBeTakenOverButActiveLeaseCannot() {
        long executionId = insertExecution(2, null);
        long now = System.currentTimeMillis();
        long leaseUntil = now + 30_000L;

        assertThat(mapper.tryLeaseExecution(executionId, 1, 2, now, leaseUntil)).isEqualTo(1);
        assertThat(mapper.tryLeaseExecution(executionId, 1, 2, now + 1, leaseUntil + 1)).isZero();
        assertThat(mapper.tryLeaseExecution(executionId, 1, 2, leaseUntil, leaseUntil + 30_000L))
                .isEqualTo(1);
    }

    @Test
    void repeatedRecoveryDoesNotPersistGroupMaterialOrTerminalResultTwice() {
        long executionId = insertExecution(3, null);
        long now = System.currentTimeMillis();

        assertThat(mapper.saveGroupNameIfAbsent(executionId, "恢复测试群", now)).isEqualTo(1);
        assertThat(mapper.saveGroupNameIfAbsent(executionId, "重复群名", now + 1)).isZero();
        assertThat(mapper.markGroupCreated(executionId, 3, "recovery-group@g.us", 4, now + 2))
                .isEqualTo(1);
        assertThat(mapper.markGroupCreated(executionId, 3, "duplicate-group@g.us", 4, now + 3))
                .isZero();

        long materialId = insertReservedMaterial(executionId, now);
        long relationId = insertExecutionMaterial(executionId, materialId, now);
        assertThat(mapper.updateMaterialEntryResult(relationId, 2, null, now + 4)).isEqualTo(1);
        assertThat(mapper.updateMaterialEntryResult(relationId, 2, null, now + 5)).isZero();
        assertThat(mapper.completeSuccessfulMaterials(executionId, now + 6)).isEqualTo(1);
        assertThat(mapper.completeSuccessfulMaterials(executionId, now + 7)).isZero();

        assertThat(mapper.markExecutionTerminal(executionId, 3, 11, null, now + 8)).isEqualTo(1);
        assertThat(mapper.markExecutionTerminal(executionId, 3, 11, null, now + 9)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT group_jid FROM group_pull_marketing_execution WHERE id = ?",
                String.class,
                executionId)).isEqualTo("recovery-group@g.us");
    }

    private long insertExecution(int stage, String groupName) {
        long now = System.currentTimeMillis();
        long seed = Math.abs(System.nanoTime());
        return insertAndReturnId("""
                INSERT INTO group_pull_marketing_execution
                    (tenant_id, task_id, builder_account_id, group_name, execution_status,
                     current_stage, stage_retry_count, next_execute_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, 0, 0, ?, ?)
                """, statement -> {
            statement.setLong(1, TEST_TENANT_ID);
            statement.setLong(2, seed);
            statement.setLong(3, seed + 1);
            statement.setString(4, groupName);
            statement.setInt(5, stage);
            statement.setLong(6, now);
            statement.setLong(7, now);
        });
    }

    private long insertReservedMaterial(long executionId, long now) {
        return insertAndReturnId("""
                INSERT INTO group_pull_marketing_material
                    (tenant_id, task_id, line_no, phone, status, current_execution_id,
                     created_at, updated_at)
                VALUES (?, ?, 1, ?, 2, ?, ?, ?)
                """, statement -> {
            statement.setLong(1, TEST_TENANT_ID);
            statement.setLong(2, executionId);
            statement.setString(3, "86" + executionId);
            statement.setLong(4, executionId);
            statement.setLong(5, now);
            statement.setLong(6, now);
        });
    }

    private long insertExecutionMaterial(long executionId, long materialId, long now) {
        return insertAndReturnId("""
                INSERT INTO group_pull_marketing_execution_material
                    (tenant_id, execution_id, material_id, allocation_no,
                     friend_status, entry_status, created_at, updated_at)
                VALUES (?, ?, ?, 1, 1, 1, ?, ?)
                """, statement -> {
            statement.setLong(1, TEST_TENANT_ID);
            statement.setLong(2, executionId);
            statement.setLong(3, materialId);
            statement.setLong(4, now);
            statement.setLong(5, now);
        });
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(statement);
            return statement;
        }, keyHolder);
        assertThat(keyHolder.getKey()).isNotNull();
        return keyHolder.getKey().longValue();
    }

    @FunctionalInterface
    private interface SqlBinder {

        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }
}
