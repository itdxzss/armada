package com.armada.marketing;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void groupCreationMarketingTablesExist() {
        assertThat(columnType("group_creation_marketing_task", "task_name")).isEqualTo("varchar");
        assertThat(columnType("group_creation_marketing_task", "status")).isEqualTo("tinyint");
        assertThat(columnType("group_creation_marketing_task", "marketing_task_id")).isEqualTo("bigint");
        assertThat(columnType("group_creation_marketing_task", "send_interval_seconds")).isEqualTo("int");
        assertThat(columnType("group_creation_marketing_item", "material_content")).isEqualTo("longtext");
        assertThat(columnType("group_creation_marketing_item", "marketing_attempt_id")).isEqualTo("bigint");
        assertThat(columnType("group_creation_marketing_item", "retry_history_json")).isEqualTo("json");
        assertThat(indexExists("group_creation_marketing_item", "idx_gcm_item_due")).isTrue();
        assertThat(indexExists("group_creation_marketing_item", "idx_gcm_item_attempt")).isTrue();
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
