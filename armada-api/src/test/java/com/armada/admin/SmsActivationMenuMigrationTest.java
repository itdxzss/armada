package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/** 验证手动接码菜单挂在每个租户的账号管理下，且重复执行不产生重复菜单。 */
class SmsActivationMenuMigrationTest {

    @Test
    void addsSmsActivationUnderEachAccountDirectoryIdempotently() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V222__sms_activation_menu.sql"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:sms_activation_menu;MODE=MySQL");
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
            for (int tenant = 1; tenant <= 2; tenant++) {
                statement.execute("INSERT INTO sys_menu (id, tenant_id, parent_id, menu_name, menu_key, menu_type, "
                        + "route_path, sort_no, status, created_at, updated_at) VALUES (" + tenant * 100 + ","
                        + tenant + ",0,'账号管理','AccountManagement','D','/account',10,1,1,1)");
            }
            statement.execute(sql);
            statement.execute(sql);

            try (var rows = statement.executeQuery("""
                    SELECT menu.*, parent.tenant_id AS parent_tenant, parent.menu_key AS parent_key
                    FROM sys_menu menu JOIN sys_menu parent ON parent.id = menu.parent_id
                    WHERE menu.menu_key = 'AccountSmsActivation'
                    """)) {
                int count = 0;
                while (rows.next()) {
                    assertThat(rows.getLong("tenant_id")).isEqualTo(rows.getLong("parent_tenant"));
                    assertThat(rows.getString("parent_key")).isEqualTo("AccountManagement");
                    assertThat(rows.getString("menu_name")).isEqualTo("手动接码");
                    assertThat(rows.getString("route_path")).isEqualTo("/account/sms-activation");
                    assertThat(rows.getString("component_path")).isEqualTo("account/sms-activation/index");
                    assertThat(rows.getString("perm_key")).isEqualTo("tenant:account:edit");
                    assertThat(rows.getInt("sort_no")).isEqualTo(45);
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }
}
