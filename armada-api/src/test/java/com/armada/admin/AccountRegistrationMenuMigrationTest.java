package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/** 验证新号注册菜单归属、租户隔离、重复执行和既有角色授权。 */
class AccountRegistrationMenuMigrationTest {

    @Test
    void addsRegistrationUnderEachAccountDirectoryWithoutChangingExistingGrants() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V194__account_registration_menu.sql"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:registration_menu;MODE=MySQL");
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sys_menu (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                        parent_id BIGINT NOT NULL, menu_name VARCHAR(64), menu_key VARCHAR(64),
                        menu_type CHAR(1), route_path VARCHAR(128), component_path VARCHAR(128),
                        perm_key VARCHAR(128), icon VARCHAR(64), sort_no INT, status TINYINT,
                        created_at BIGINT, created_by BIGINT, updated_at BIGINT, updated_by BIGINT,
                        UNIQUE (tenant_id, menu_key), UNIQUE (tenant_id, route_path)
                    )
                    """);
            statement.execute("CREATE TABLE sys_role_menu (tenant_id BIGINT, role_id BIGINT, menu_id BIGINT)");
            for (int tenant = 1; tenant <= 2; tenant++) {
                int base = tenant * 100;
                statement.execute("INSERT INTO sys_menu "
                        + "(id, tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, "
                        + "sort_no, status, created_at, updated_at) VALUES "
                        + "(" + base + "," + tenant + ",0,'账号管理','AccountManagement','D','/account',10,1,1,1),"
                        + "(" + (base + 1) + "," + tenant + ",0,'任务中心','TaskCenter','D','/task',30,1,1,1),"
                        + "(" + (base + 2) + "," + tenant + "," + (base + 1)
                        + ",'账号导入','AccountImport','M','/account/import',10,1,1,1)");
                statement.execute("INSERT INTO sys_role_menu VALUES (" + tenant + "," + tenant
                        + "," + (base + 2) + ")");
            }
            statement.execute(sql);
            statement.execute(sql);

            try (var rows = statement.executeQuery("""
                    SELECT registration.*, parent.tenant_id AS parent_tenant, parent.menu_key AS parent_key
                    FROM sys_menu registration JOIN sys_menu parent ON parent.id = registration.parent_id
                    WHERE registration.menu_key = 'AccountRegistration'
                    """)) {
                int count = 0;
                while (rows.next()) {
                    assertThat(rows.getLong("tenant_id")).isEqualTo(rows.getLong("parent_tenant"));
                    assertThat(rows.getString("parent_key")).isEqualTo("AccountManagement");
                    assertThat(rows.getString("menu_name")).isEqualTo("新号注册");
                    assertThat(rows.getString("menu_type")).isEqualTo("M");
                    assertThat(rows.getString("route_path")).isEqualTo("/account/registration");
                    assertThat(rows.getString("component_path")).isEqualTo("account/registration/index");
                    assertThat(rows.getString("perm_key")).isEqualTo("tenant:account:edit");
                    assertThat(rows.getInt("sort_no")).isEqualTo(40);
                    assertThat(rows.getInt("status")).isEqualTo(1);
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("""
                    SELECT grants.tenant_id, grants.role_id, granted.menu_key,
                           parent.menu_key AS parent_key
                    FROM sys_role_menu grants JOIN sys_menu granted ON granted.id = grants.menu_id
                    JOIN sys_menu parent ON parent.id = granted.parent_id ORDER BY grants.tenant_id
                    """)) {
                for (int tenant = 1; tenant <= 2; tenant++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("tenant_id")).isEqualTo(tenant);
                    assertThat(rows.getLong("role_id")).isEqualTo(tenant);
                    assertThat(rows.getString("menu_key")).isEqualTo("AccountImport");
                    assertThat(rows.getString("parent_key")).isEqualTo("TaskCenter");
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
