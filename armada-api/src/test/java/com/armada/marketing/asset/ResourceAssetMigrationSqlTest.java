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
