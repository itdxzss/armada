package com.armada.marketing.grouppull;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉群营销数据库迁移脚本结构测试。 */
class GroupPullMarketingMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V070__group_pull_marketing.sql");

    private static final Path MATERIAL_INTERVAL_MIGRATION = Path.of(
            "src/main/resources/db/migration/V080__group_pull_material_entry_interval.sql");

    @Test
    void migrationDefinesOnlyConfirmedGroupPullFacts() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("business_type TINYINT NOT NULL DEFAULT 1")
                .contains("marketing_occupancy_task_id BIGINT DEFAULT NULL")
                .contains("CREATE TABLE IF NOT EXISTS group_pull_marketing_task")
                .contains("CREATE TABLE IF NOT EXISTS group_pull_marketing_execution")
                .contains("CREATE TABLE IF NOT EXISTS group_pull_marketing_material")
                .contains("CREATE TABLE IF NOT EXISTS group_pull_marketing_execution_material")
                .contains("CREATE TABLE IF NOT EXISTS group_pull_marketing_account_stat")
                .contains("active_builder_account_id BIGINT GENERATED ALWAYS AS")
                .contains("UNIQUE KEY uq_gpme_active_builder")
                .doesNotContain("next_group_sequence")
                .doesNotContain("group_link_health_status");
    }

    @Test
    void materialEntryIntervalMigrationAddsBackwardCompatibleDefaultWithGuard() throws IOException {
        assertThat(MATERIAL_INTERVAL_MIGRATION).exists();
        String sql = Files.readString(MATERIAL_INTERVAL_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("column_name = 'material_entry_interval_seconds'")
                .contains("ADD COLUMN material_entry_interval_seconds INT NOT NULL DEFAULT 300")
                .contains("AFTER material_per_group");
    }
}
