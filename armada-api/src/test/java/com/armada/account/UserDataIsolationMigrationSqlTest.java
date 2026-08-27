package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 第一阶段账号归属字段和唯一范围的 Flyway 结构契约。 */
class UserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V140__account_user_data_ownership.sql";

    /**
     * 验证 V140 只给权限根增加 owner，并保持手机号租户级唯一。
     *
     * @throws IOException 迁移资源无法读取时抛出
     */
    @Test
    void v140AddsOwnerRootsAndScopesAccountGroupNamesPerOwner() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'account'")
                    .contains("table_name = 'account_group'")
                    .contains("table_name = 'account_import_batch'")
                    .contains("column_name = 'owner_user_id'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_account_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_account_group_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("idx_account_import_batch_owner (tenant_id, owner_user_id, deleted_at, created_at, id)")
                    .contains("drop index uq_tenant_name")
                    .contains("uq_account_group_owner_name (tenant_id, owner_user_id, name, is_active)");

            assertThat(sql)
                    .contains("add column unowned_name_key varchar(100) generated always as (if(owner_user_id is null, name, null)) virtual")
                    .contains("uq_account_group_unowned_name (tenant_id, unowned_name_key, is_active)")
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("prepare account_owner_column_stmt")
                    .contains("deallocate prepare account_group_drop_legacy_unique_stmt")
                    .doesNotContain("uq_tenant_phone")
                    .doesNotContain("update account set owner_user_id")
                    .doesNotContain("update account_group set owner_user_id")
                    .doesNotContain("update account_import_batch set owner_user_id");

            assertThat(sql)
                    .contains("group_concat(column_name order by seq_in_index separator ',')")
                    .contains("select max(non_unique)")
                    .contains("create temporary table tmp_v140_account_group_index_guard")
                    .contains("insert into tmp_v140_account_group_index_guard (guard_key) select 1")
                    .contains("@account_group_owner_unique_columns = 'tenant_id,owner_user_id,name,is_active'")
                    .contains("@account_group_unowned_unique_columns = 'tenant_id,unowned_name_key,is_active'");
            assertThat(sql.indexOf("create temporary table tmp_v140_account_group_index_guard"))
                    .isLessThan(sql.indexOf("alter table account_group drop index uq_tenant_name"));
        }
    }
}
