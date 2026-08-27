package com.armada.hyperlink.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 超链模板 V142 迁移脚本的结构契约测试。 */
class HyperlinkTemplateMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V142__hyperlink_template.sql");

    @Test
    void migrationCreatesOnlyHyperlinkTemplateWithFrozenColumnsAndIndexes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS hyperlink_template")
                .contains("tenant_id BIGINT NOT NULL")
                .contains("template_name VARCHAR(128) NOT NULL")
                .contains("message_type TINYINT NOT NULL")
                .contains("message_schema_version INT NOT NULL DEFAULT 1")
                .contains("title VARCHAR(512) NOT NULL")
                .contains("content TEXT")
                .contains("link_description VARCHAR(512)")
                .contains("promotion_link VARCHAR(2048)")
                .contains("buttons JSON")
                .contains("card_text VARCHAR(500)")
                .contains("link_preview_asset_id BIGINT")
                .contains("body_main_asset_id BIGINT")
                .contains("remark VARCHAR(255)")
                .contains("version INT NOT NULL DEFAULT 1")
                .contains("created_by BIGINT")
                .contains("created_at BIGINT NOT NULL")
                .contains("updated_at BIGINT NOT NULL")
                .contains("deleted_at BIGINT")
                .contains("GENERATED ALWAYS AS")
                .contains("UNIQUE KEY uq_hyperlink_template_name (tenant_id, template_name, is_active)")
                .contains("KEY idx_hyperlink_template_type (tenant_id, message_type, deleted_at, id)")
                .contains("KEY idx_hyperlink_template_created (tenant_id, created_at, id)")
                .doesNotContain("data_package")
                .doesNotContain("sys_menu")
                .doesNotContain("sys_role");
    }
}
