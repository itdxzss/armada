package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 系统管理 RBAC 数据库迁移脚本结构测试。 */
class SystemManagementMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V062__system_management_rbac.sql");

    @Test
    void migrationCreatesMinimalTenantRbacSchema() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS sys_user")
                .contains("CREATE TABLE IF NOT EXISTS sys_role")
                .contains("CREATE TABLE IF NOT EXISTS sys_menu")
                .contains("CREATE TABLE IF NOT EXISTS sys_user_role")
                .contains("CREATE TABLE IF NOT EXISTS sys_role_menu")
                .contains("UNIQUE KEY uq_sys_user_tenant_username (tenant_id, username)")
                .contains("UNIQUE KEY uq_sys_role_tenant_code (tenant_id, role_code)")
                .contains("UNIQUE KEY uq_sys_menu_tenant_key (tenant_id, menu_key)")
                .contains("PRIMARY KEY (tenant_id, user_id, role_id)")
                .contains("PRIMARY KEY (tenant_id, role_id, menu_id)")
                .doesNotContain("deleted_at")
                .doesNotContain("is_active");
    }

    @Test
    void migrationSeedsTenantAdminAndRegroupedMenuTree() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("TENANT_ADMIN")
                .contains("'D'")
                .contains("'M'")
                .contains("'B'")
                .contains("'AccountManagement'")
                .contains("'GroupManagement'")
                .contains("'TaskCenter'")
                .contains("'MaterialManagement'")
                .contains("'ResourceManagement'")
                .contains("'BuyerGrowth'")
                .contains("'SystemManagement'")
                .contains("'AccountIndex'")
                .contains("'SystemUser'")
                .contains("'SystemRole'")
                .contains("'SystemMenu'")
                .contains("tenant:buyer-channel:create")
                .contains("tenant:system-user:view")
                .contains("tenant:system-role:view")
                .contains("tenant:system-menu:view")
                .doesNotContain("INSERT INTO sys_role_menu");
    }
}
