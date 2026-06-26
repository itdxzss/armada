package com.armada.task.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

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
}
