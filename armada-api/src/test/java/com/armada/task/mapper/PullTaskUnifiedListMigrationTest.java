package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉群任务统一列表字段、营销统计和全局设置的迁移合同测试。 */
class PullTaskUnifiedListMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql");
    private static final Path FORWARD_COMPANION = Path.of(
            "../.harness/changes/pull-task-unified-list-global-settings/db-migrations.sql");
    private static final Path ROLLBACK = Path.of(
            "../.harness/changes/pull-task-unified-list-global-settings/rollback.sql");

    @Test
    void addsGuardedCommonMetadataWithoutInventingExecutionTime() throws IOException {
        String sql = readRequired(MIGRATION);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("task_type VARCHAR(32) NOT NULL DEFAULT ''STANDARD''")
                .contains("group_source VARCHAR(32) DEFAULT NULL")
                .contains("primary_stage VARCHAR(64) DEFAULT NULL")
                .contains("blocking_reason VARCHAR(255) DEFAULT NULL")
                .contains("last_business_executed_at BIGINT DEFAULT NULL")
                .contains("information_schema.statistics")
                .contains("idx_pull_task_type_status")
                .contains("idx_pull_task_source")
                .doesNotContain("last_business_executed_at = created_at")
                .doesNotContain("last_business_executed_at = updated_at");
    }

    @Test
    void createsMarketingSummaryAndUnconfiguredTenantSetting() throws IOException {
        String sql = readRequired(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_marketing_summary")
                .contains("PRIMARY KEY (tenant_id, task_id)")
                .contains("message_unknown_count INT NOT NULL DEFAULT 0")
                .contains("is_marketing_admin_shortage TINYINT(1) NOT NULL DEFAULT 0")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_marketing_setting")
                .contains("marketing_silence_minutes INT NOT NULL COMMENT")
                .contains("group_lockdown_minutes INT NOT NULL COMMENT")
                .contains("max_marketing_accounts_per_group INT NOT NULL COMMENT")
                .doesNotContain("marketing_silence_minutes INT NOT NULL DEFAULT")
                .doesNotContain("group_lockdown_minutes INT NOT NULL DEFAULT")
                .doesNotContain("max_marketing_accounts_per_group INT NOT NULL DEFAULT");
    }

    @Test
    void seedsDedicatedSettingPermissionAndProvidesReviewableRollback() throws IOException {
        String migration = readRequired(MIGRATION);
        String forward = readRequired(FORWARD_COMPANION);
        String rollback = readRequired(ROLLBACK);

        assertThat(migration)
                .contains("TaskPullSettings")
                .contains("tenant:pull_task:settings")
                .contains("parent.menu_key = 'TaskPull'");
        assertThat(forward)
                .contains("pull_task_group_marketing_summary")
                .contains("pull_task_group_marketing_setting")
                .contains("tenant:pull_task:settings");
        assertThat(rollback)
                .contains("menu_key = 'TaskPullSettings'")
                .contains("DROP TABLE IF EXISTS pull_task_group_marketing_setting")
                .contains("DROP TABLE IF EXISTS pull_task_group_marketing_summary")
                .contains("DROP COLUMN task_type");
    }

    private static String readRequired(Path path) throws IOException {
        assertThat(path).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
