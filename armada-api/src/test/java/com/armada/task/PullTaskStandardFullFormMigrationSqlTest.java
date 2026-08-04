package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通群链接完整表单持久化迁移的 SQL 合同测试。 */
class PullTaskStandardFullFormMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V095__pull_task_standard_full_form_settings.sql");

    @Test
    void addsEveryExecutionSettingColumnWithIdempotentGuards() throws IOException {
        assertThat(sql())
                .contains("column_name = 'source_group_folder_id'")
                .contains("ADD COLUMN source_group_folder_id BIGINT DEFAULT NULL")
                .contains("column_name = 'source_group_folder_name'")
                .contains("ADD COLUMN source_group_folder_name VARCHAR(100) DEFAULT NULL")
                .contains("column_name = 'puller_sync_mode'")
                .contains("ADD COLUMN puller_sync_mode TINYINT NOT NULL DEFAULT 1")
                .contains("column_name = 'is_clear_existing_members'")
                .contains("ADD COLUMN is_clear_existing_members TINYINT(1) NOT NULL DEFAULT 0")
                .contains("column_name = 'manager_finish_group_id'")
                .contains("ADD COLUMN manager_finish_group_id BIGINT DEFAULT NULL")
                .contains("column_name = 'manager_finish_group_name'")
                .contains("ADD COLUMN manager_finish_group_name VARCHAR(100) DEFAULT NULL")
                .contains("column_name = 'puller_finish_group_id'")
                .contains("ADD COLUMN puller_finish_group_id BIGINT DEFAULT NULL")
                .contains("column_name = 'puller_finish_group_name'")
                .contains("ADD COLUMN puller_finish_group_name VARCHAR(100) DEFAULT NULL");
    }

    @Test
    void alignsFolderNameAndMakesStationSnapshotsNullable() throws IOException {
        assertThat(sql())
                .contains("ALTER TABLE group_folder MODIFY COLUMN name VARCHAR(100) NOT NULL")
                .contains("ALTER TABLE pull_task_standard_setting "
                        + "MODIFY COLUMN station_group_id BIGINT DEFAULT NULL")
                .contains("ALTER TABLE pull_task_standard_setting "
                        + "MODIFY COLUMN station_group_name VARCHAR(100) DEFAULT NULL");
    }

    @Test
    void createsOneGroupSettingRowPerTaskWithOneAvatarReference() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE IF NOT EXISTS pull_task_standard_group_setting")
                .contains("avatar_file_key VARCHAR(512) DEFAULT NULL")
                .contains("UNIQUE KEY uq_pull_task_standard_group_setting_task "
                        + "(tenant_id, task_id)")
                .contains("UNIQUE KEY uq_pull_task_standard_group_setting_avatar "
                        + "(tenant_id, avatar_file_key)")
                .doesNotContain("pull_task_group_avatar_file");
    }

    @Test
    void groupSettingContainsEveryApprovedProfileAndPermissionField() throws IOException {
        assertThat(sql())
                .contains("setting_timing TINYINT NOT NULL DEFAULT 2")
                .contains("group_name VARCHAR(128) DEFAULT NULL")
                .contains("is_material_filename_as_group_name TINYINT(1) NOT NULL DEFAULT 0")
                .contains("group_description VARCHAR(1024) DEFAULT NULL")
                .contains("is_auto_unmute_after_task TINYINT(1) NOT NULL DEFAULT 0")
                .contains("is_auto_close_invite_after_task TINYINT(1) NOT NULL DEFAULT 0")
                .contains("edit_permission_mode TINYINT NOT NULL DEFAULT 0")
                .contains("mute_mode TINYINT NOT NULL DEFAULT 0")
                .contains("link_permission_mode TINYINT NOT NULL DEFAULT 2")
                .contains("disappearing_message_mode TINYINT NOT NULL DEFAULT 0");
    }

    private static String sql() throws IOException {
        assertThat(MIGRATION)
                .as("完整表单设置必须使用独立增量迁移")
                .exists();
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
