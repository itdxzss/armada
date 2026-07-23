package com.armada.marketing.grouppull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.testsupport.DbTestBase;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 拉群营销表结构与活动建群账号唯一约束真库测试。 */
class GroupPullMarketingSchemaDbTest extends DbTestBase {

    private static final List<String> TABLES = List.of(
            "group_pull_marketing_task",
            "group_pull_marketing_execution",
            "group_pull_marketing_material",
            "group_pull_marketing_execution_material",
            "group_pull_marketing_account_stat");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationCreatesFiveTablesAndPublicColumns() {
        assertThat(columnExists("marketing_task", "business_type")).isTrue();
        assertThat(columnExists("account_group", "marketing_occupancy_task_id")).isTrue();
        assertThat(TABLES).allMatch(this::tableExists);
    }

    @Test
    void activeBuilderUniqueKeyAllowsReuseOnlyAfterRelease() {
        long firstExecutionId = insertExecution(91001L, 92001L);

        assertThatThrownBy(() -> insertExecution(91002L, 92001L))
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);

        jdbc.update("UPDATE group_pull_marketing_execution SET released_at = ? WHERE id = ?",
                System.currentTimeMillis(), firstExecutionId);

        assertThatCode(() -> insertExecution(91002L, 92001L)).doesNotThrowAnyException();
    }

    private long insertExecution(long taskId, long builderAccountId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO group_pull_marketing_execution (
                    tenant_id, task_id, builder_account_id, execution_status,
                    current_stage, stage_retry_count, next_execute_at, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 1, 0, 0, ?, ?)
                """, TEST_TENANT_ID, taskId, builderAccountId, now, now);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private boolean tableExists(String tableName) {
        return count("information_schema.tables", "table_name", tableName) == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count == 1;
    }

    private int count(String metadataTable, String columnName, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + metadataTable
                        + " WHERE table_schema = DATABASE() AND " + columnName + " = ?",
                Integer.class,
                value);
        return count == null ? 0 : count;
    }
}
