package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 群组列表运营分组迁移脚本结构测试。 */
class GroupFolderMigrationSqlTest {

    @Test
    void v090CreatesTenantFolderAndGuardsGroupLinkColumnAndIndex() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V090__group_folder.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("create table if not exists group_folder")
                    .contains("tenant_id bigint not null")
                    .contains("unique key uq_group_folder_name (tenant_id, name)")
                    .contains("information_schema.columns")
                    .contains("column_name = 'folder_id'")
                    .contains("add column folder_id bigint default null")
                    .contains("information_schema.statistics")
                    .contains("index_name = 'idx_group_link_folder'")
                    .contains("idx_group_link_folder (tenant_id, deleted_at, folder_id)");
        }
    }
}
