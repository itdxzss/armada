package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 新建普群加好友失败明细保留字段的 Flyway 脚本契约测试。 */
class NormalGroupCreationContactFailureMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V110__normal_group_creation_contact_failure_detail.sql");

    @Test
    void migrationAddsIdempotentPerDirectionErrorColumnsAndItemFlag() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("column_name = 'creator_save_error_code'")
                .contains("ADD COLUMN creator_save_error_code VARCHAR(64) DEFAULT NULL")
                .contains("column_name = 'creator_save_error_message'")
                .contains("ADD COLUMN creator_save_error_message VARCHAR(512) DEFAULT NULL")
                .contains("column_name = 'member_save_error_code'")
                .contains("ADD COLUMN member_save_error_code VARCHAR(64) DEFAULT NULL")
                .contains("column_name = 'member_save_error_message'")
                .contains("ADD COLUMN member_save_error_message VARCHAR(512) DEFAULT NULL")
                .contains("column_name = 'contact_prepare_failed'")
                .contains("ADD COLUMN contact_prepare_failed TINYINT NOT NULL DEFAULT 0");
    }

    @Test
    void migrationBackfillsLegacyReasonsPerDirectionWithoutOverwritingNewColumns()
            throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("SET creator_save_error_code = last_error_code")
                .contains("AND creator_save_error_code IS NULL")
                .contains("AND creator_saved_member_status IN ('FAILED', 'UNKNOWN')")
                .contains("SET member_save_error_code = last_error_code")
                .contains("AND member_save_error_code IS NULL")
                .contains("AND member_saved_creator_status IN ('FAILED', 'UNKNOWN')")
                .contains("SET item.contact_prepare_failed = 1")
                .contains("WHERE item.current_step <> 'PREPARING_CONTACTS'");
    }
}
