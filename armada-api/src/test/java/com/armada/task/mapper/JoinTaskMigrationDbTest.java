package com.armada.task.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V007 建表验证:确认 join_task + join_task_result 表存在,且所有时间列为 BIGINT epoch 毫秒。
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

    private String queryDataType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                String.class, tableName, columnName);
    }
}
