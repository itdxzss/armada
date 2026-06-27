package com.armada.group.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupListDataModelMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void groupLink_hasOriginAndMembershipColumns() {
        assertThat(columnExists("group_link", "origin")).isTrue();
        assertThat(columnExists("group_link", "membership_state")).isTrue();
        assertThat(columnType("group_link", "origin")).isEqualTo("tinyint");
        assertThat(columnType("group_link", "membership_state")).isEqualTo("tinyint");
    }

    @Test
    void previewAndHealthTablesExist() {
        assertThat(tableExists("group_link_preview")).isTrue();
        assertThat(columnExists("group_link_preview", "group_jid")).isTrue();
        assertThat(columnExists("group_link_preview", "wa_subject")).isTrue();
        assertThat(tableExists("group_link_health")).isTrue();
        assertThat(columnExists("group_link_health", "health_status")).isTrue();
        assertThat(columnExists("group_link_health", "is_banned")).isTrue();
    }

    @Test
    void importBatch_usesDuplicateRowsInsteadOfSkippedRows() {
        assertThat(columnExists("group_link_import_batch", "duplicate_rows")).isTrue();
        assertThat(columnExists("group_link_import_batch", "skipped_rows")).isFalse();
    }

    @Test
    void importDetail_hasNewResultDimensions() {
        assertThat(columnExists("group_link_import_detail", "success_type")).isTrue();
        assertThat(columnExists("group_link_import_detail", "existing_origin")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }
}
