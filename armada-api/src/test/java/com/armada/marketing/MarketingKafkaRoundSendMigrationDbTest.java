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
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_round")).isFalse();
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_no")).isFalse();
    }

    @Test
    void marketingTargetSupportsMixedAccountAndGroupScopes() {
        assertThat(columnType("marketing_task_target", "target_scope")).isEqualTo("tinyint");
        assertThat(isNullable("marketing_task_target", "group_link_id")).isTrue();
        assertThat(isNullable("marketing_task_target", "group_jid")).isTrue();
        assertThat(isNullable("marketing_task_target", "group_link_url")).isTrue();
        assertThat(indexExists("marketing_task_target", "uq_marketing_task_target_scope")).isTrue();
    }

    @Test
    void marketingAttemptStoresResolvedGroupSnapshot() {
        assertThat(columnType("marketing_task_send_attempt", "group_link_id")).isEqualTo("bigint");
        assertThat(columnType("marketing_task_send_attempt", "group_jid")).isEqualTo("varchar");
        assertThat(columnType("marketing_task_send_attempt", "group_name")).isEqualTo("varchar");
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_group_round")).isTrue();
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

    private boolean isNullable(String tableName, String columnName) {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
        return "YES".equals(nullable);
    }
}
