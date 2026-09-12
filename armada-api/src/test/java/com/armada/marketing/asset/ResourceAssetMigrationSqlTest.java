package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 图片素材库 V157 表结构、索引和菜单权限合同测试。 */
class ResourceAssetMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V157__hyperlink_image_asset_library.sql");

    @Test
    void groupsMigrationIsAdditiveTenantScopedAndGuardsColumnAndIndex() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V189__resource_asset_groups.sql"));
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS resource_asset_group", "tenant_id BIGINT NOT NULL",
                "UNIQUE KEY uk_resource_asset_group_name (tenant_id, group_name)",
                "information_schema.columns", "information_schema.statistics", "ADD COLUMN group_id BIGINT NULL",
                "(tenant_id, group_id, deleted_at, created_at, id)")
                .doesNotContain("DROP TABLE", "DELETE FROM", "UPDATE marketing_template_file");
    }

    @Test
    void businessScopeMigrationKeepsExistingAssetsSharedAndSeparatesGroupMembership() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V190__resource_asset_business_scope.sql"));
        assertThat(sql).contains("ADD COLUMN asset_scope TINYINT NULL", "(tenant_id, scope, group_name)",
                "PRIMARY KEY (tenant_id, file_id, scope)", "INSERT IGNORE INTO resource_asset_group_ref",
                "ALTER TABLE marketing_template_file DROP COLUMN group_id", "idx_asset_scope_page")
                .doesNotContain("SET asset_scope", "DELETE FROM marketing_template_file", "DROP TABLE marketing_template_file");
    }

    @Test
    void migrationAddsMetadataTagsReferenceIndexesAndRbac() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("asset_name VARCHAR(128)")
                .contains("width INT")
                .contains("height INT")
                .contains("created_by BIGINT")
                .contains("updated_at BIGINT")
                .contains("CREATE TABLE IF NOT EXISTS resource_asset_tag")
                .contains("tag_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL")
                .contains("CREATE TABLE IF NOT EXISTS resource_asset_tag_ref")
                .contains("idx_marketing_template_file_name")
                .contains("idx_marketing_template_image_file")
                .contains("idx_hyperlink_template_link_asset")
                .contains("idx_hyperlink_template_body_asset")
                .contains("'/hyperlink/library'")
                .contains("'hyperlink/library/index'")
                .contains(
                        "tenant:resource_asset:view",
                        "tenant:resource_asset:upload",
                        "tenant:resource_asset:edit",
                        "tenant:resource_asset:delete")
                .doesNotContain("hyperlink_task_content");
    }
}
