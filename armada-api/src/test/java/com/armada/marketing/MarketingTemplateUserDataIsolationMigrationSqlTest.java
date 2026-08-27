package com.armada.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 营销模板和图片文件用户归属的 Flyway 结构契约。 */
class MarketingTemplateUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V141__marketing_template_user_data_ownership.sql";

    @Test
    void v141AddsOwnerRootsAndScopesActiveTemplateNamesPerOwner() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'marketing_template'")
                    .contains("table_name = 'marketing_template_file'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_marketing_template_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_marketing_template_file_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("active_name_key varchar(128) generated always as (if(deleted_at is null, template_name, null)) virtual")
                    .contains("unowned_name_key varchar(128) generated always as (if(deleted_at is null and owner_user_id is null, template_name, null)) virtual")
                    .contains("uq_marketing_template_owner_name (tenant_id, owner_user_id, active_name_key)")
                    .contains("uq_marketing_template_unowned_name (tenant_id, unowned_name_key)")
                    .contains("alter table marketing_template drop index uq_tenant_name");

            assertThat(sql)
                    .contains("create temporary table tmp_v141_marketing_template_index_guard")
                    .contains("@marketing_template_owner_unique_columns = 'tenant_id,owner_user_id,active_name_key'")
                    .contains("@marketing_template_unowned_unique_columns = 'tenant_id,unowned_name_key'")
                    .doesNotContain("update marketing_template set owner_user_id")
                    .doesNotContain("update marketing_template_file set owner_user_id");
            assertThat(sql.indexOf("create temporary table tmp_v141_marketing_template_index_guard"))
                    .isLessThan(sql.indexOf("alter table marketing_template drop index uq_tenant_name"));
        }
    }
}
