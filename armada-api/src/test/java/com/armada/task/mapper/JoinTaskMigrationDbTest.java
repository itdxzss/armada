package com.armada.task.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进群任务真实 MySQL 迁移验证。
 *
 * <p>覆盖 V007 建表和 V055 Kafka 派发状态扩展，验证字段类型、默认值和调度索引的真实库结构，
 * 不用 H2 或字符串匹配替代 MySQL DDL 执行。</p>
 */
class JoinTaskMigrationDbTest extends DbTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v007_createsJoinTaskTables() {
        Integer t1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='join_task'",
                Integer.class);
        Integer t2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='join_task_result'",
                Integer.class);
        assertThat(t1).isEqualTo(1);
        assertThat(t2).isEqualTo(1);
    }

    @Test
    void joinTask_timeColumnsAreBigint() {
        String type = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='join_task' AND column_name='created_at'",
                String.class);
        assertThat(type).isEqualTo("bigint");
    }

    @Test
    void additionalTimeColumns_areBigint() {
        // join_task.updated_at
        assertThat(queryDataType("join_task", "updated_at")).isEqualTo("bigint");
        // join_task.deleted_at
        assertThat(queryDataType("join_task", "deleted_at")).isEqualTo("bigint");
        // join_task_result.created_at
        assertThat(queryDataType("join_task_result", "created_at")).isEqualTo("bigint");
        // join_task_result.updated_at
        assertThat(queryDataType("join_task_result", "updated_at")).isEqualTo("bigint");
        // join_task_result.promoted_at
        assertThat(queryDataType("join_task_result", "promoted_at")).isEqualTo("bigint");
    }

    @Test
    void joinTaskResult_hasKafkaDispatchColumnsAndIndexes() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='join_task_result'",
                Integer.class);
        assertThat(count).isEqualTo(17);
        assertColumn("dispatch_state", "varchar", false, "WAITING");
        assertColumn("next_execute_at", "bigint", true, null);
        assertColumn("command_id", "varchar", true, null);
        assertColumn("attempt_no", "int", false, "0");
        assertIndex("idx_jtr_dispatch", "dispatch_state,next_execute_at,id");
        assertIndex("idx_jtr_task_account", "tenant_id,join_task_id,account_id,status,id");
    }

    private void assertColumn(String column, String type, boolean nullable, String defaultValue) {
        var row = jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='join_task_result' AND column_name=?",
                column);
        assertThat(row.get("data_type")).isEqualTo(type);
        assertThat(row.get("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
        assertThat(row.get("column_default")).isEqualTo(defaultValue);
    }

    private void assertIndex(String index, String expectedColumns) {
        String columns = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name='join_task_result' AND index_name=?",
                String.class, index);
        assertThat(columns).isEqualTo(expectedColumns);
    }

    private String queryDataType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                String.class, tableName, columnName);
    }
}
