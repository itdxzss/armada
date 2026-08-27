package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 群域用户归属字段和用户范围唯一键的 Flyway 结构契约。 */
class GroupUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V148__group_user_data_ownership.sql";

    /**
     * 验证 V148 只增加权限根 owner，不根据 created_by 猜测历史归属。
     *
     * @throws IOException 迁移资源无法读取时抛出
     */
    @Test
    void v148AddsGroupOwnerRootsAndMovesBusinessUniquenessIntoOwnerScope()
            throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'group_link'")
                    .contains("table_name = 'group_folder'")
                    .contains("table_name = 'group_link_label'")
                    .contains("table_name = 'group_link_import_batch'")
                    .contains("table_name = 'group_batch_task'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_group_link_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_group_folder_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_group_link_label_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_group_link_import_batch_owner (tenant_id, owner_user_id, deleted_at, created_at, id)")
                    .contains("idx_group_batch_task_owner (tenant_id, owner_user_id, status, id)");

            assertThat(sql)
                    .contains("uq_group_link_owner_url (tenant_id, owner_user_id, link_url)")
                    .contains("uq_group_link_unowned_url (tenant_id, unowned_url_key)")
                    .contains("drop index uq_url")
                    .contains("uq_group_folder_owner_name (tenant_id, owner_user_id, name)")
                    .contains("uq_group_folder_unowned_name (tenant_id, unowned_name_key)")
                    .contains("drop index uq_group_folder_name")
                    .contains("uq_group_link_label_owner_name (tenant_id, owner_user_id, name)")
                    .contains("uq_group_link_label_unowned_name (tenant_id, unowned_name_key)")
                    .contains("drop index uq_name")
                    .contains("uq_group_batch_task_owner_request (tenant_id, owner_user_id, request_id)")
                    .contains("uq_group_batch_task_unowned_request (tenant_id, unowned_request_key)")
                    .contains("drop index uq_group_batch_task_request");

            assertThat(sql)
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("group_concat(column_name order by seq_in_index separator ',')")
                    .contains("select max(non_unique)")
                    .contains("create temporary table tmp_v147_group_link_index_guard")
                    .contains("create temporary table tmp_v147_group_folder_index_guard")
                    .contains("create temporary table tmp_v147_group_link_label_index_guard")
                    .contains("create temporary table tmp_v147_group_batch_task_index_guard")
                    .doesNotContain("update group_link set owner_user_id")
                    .doesNotContain("update group_folder set owner_user_id")
                    .doesNotContain("update group_link_label set owner_user_id")
                    .doesNotContain("update group_link_import_batch set owner_user_id")
                    .doesNotContain("update group_batch_task set owner_user_id");

            assertThat(sql.indexOf("uq_group_link_owner_url"))
                    .isLessThan(sql.indexOf("drop index uq_url"));
            assertThat(sql.indexOf("uq_group_folder_owner_name"))
                    .isLessThan(sql.indexOf("drop index uq_group_folder_name"));
            assertThat(sql.indexOf("uq_group_link_label_owner_name"))
                    .isLessThan(sql.indexOf("drop index uq_name"));
            assertThat(sql.indexOf("uq_group_batch_task_owner_request"))
                    .isLessThan(sql.indexOf("drop index uq_group_batch_task_request"));
        }
    }
}
