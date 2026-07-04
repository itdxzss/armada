package com.armada.marketing;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingKafkaRoundSendMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void marketingTaskHasRoundSchedulerColumns() {
        assertThat(columnType("marketing_task", "current_round_no")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "next_round_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "last_round_started_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task", "idx_marketing_task_round_due")).isTrue();
    }

    @Test
    void marketingAttemptHasRoundAndProtocolResultColumns() {
        assertThat(columnType("marketing_task_send_attempt", "round_no")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "command_id")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "message_id")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "submitted_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "result_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_round")).isTrue();
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_no")).isFalse();
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class,
                tableName,
                indexName);
        return count != null && count > 0;
    }
}
