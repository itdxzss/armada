package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群组列表历史分类与详情快照迁移 SQL 契约测试。 */
class GroupListHistoryMetadataMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V096__group_list_history_metadata.sql");

    @Test
    void migrationCreatesClassificationMetadataAndSnapshotStructures() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "is_historical",
                "is_post_control",
                "continent_code",
                "wa_description",
                "admin_only_edit_info",
                "member_add_mode",
                "join_approval_mode",
                "ephemeral_duration_seconds",
                "creator_country_iso2",
                "creator_continent_code",
                "metadata_observed_at",
                "CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot",
                "CREATE TABLE IF NOT EXISTS group_metadata_sync_task",
                "execution_account_id",
                "rerun_requested",
                "idx_group_link_historical",
                "idx_group_link_post_control",
                "idx_country_continent_sort",
                "UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id)");
        assertThat(sql).doesNotContain("group_created_at = created_at");
    }
}
