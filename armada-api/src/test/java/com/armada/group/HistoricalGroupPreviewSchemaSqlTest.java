package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 历史群预览字段迁移与 Mapper SQL 合同测试。 */
class HistoricalGroupPreviewSchemaSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V086__historical_group_created_at.sql");

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupLinkPreviewMapper.xml");

    @Test
    void migrationAddsNullableCreatedAtWithInformationSchemaGuard() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("group_link_preview")
                .contains("group_created_at")
                .contains("BIGINT")
                .contains("DEFAULT NULL");
    }

    @Test
    void previewUpsertPersistsCreatedAtWithoutErasingKnownValueWithNull() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("group_created_at")
                .contains("#{groupCreatedAt}")
                .contains("COALESCE(VALUES(group_created_at), group_created_at)");
    }
}
