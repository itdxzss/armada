package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/** 验证动态营销提升为一级菜单时保留各租户的子菜单与授权关联。 */
class FeedMarketingMenuMigrationTest {

    @Test
    void promotesEachTenantDirectoryWithoutChangingChildrenOrGrants() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V184__promote_feed_marketing_menu.sql"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:feed_menu;MODE=MySQL");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sys_menu (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                    + "parent_id BIGINT, menu_key VARCHAR(64), menu_type VARCHAR(1), sort_no INT)");
            statement.execute("CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT)");
            for (int tenant = 1; tenant <= 2; tenant++) {
                int base = tenant * 100;
                statement.execute("INSERT INTO sys_menu VALUES "
                        + "(" + base + "," + tenant + ",0,'TaskCenter','D',30),"
                        + "(" + (base + 1) + "," + tenant + "," + base + ",'TaskFeedMarketing','D',60),"
                        + "(" + (base + 2) + "," + tenant + "," + (base + 1) + ",'TaskFeed','M',10),"
                        + "(" + (base + 3) + "," + tenant + ",0,'MaterialManagement','D',40)");
                statement.execute("INSERT INTO sys_role_menu VALUES (" + tenant + "," + (base + 1) + ")");
            }
            statement.execute(sql);
            statement.execute(sql);
            try (var rows = statement.executeQuery("SELECT m.id, m.parent_id, m.sort_no, "
                    + "c.parent_id AS child_parent, r.menu_id FROM sys_menu m "
                    + "JOIN sys_menu c ON c.tenant_id=m.tenant_id AND c.menu_key='TaskFeed' "
                    + "JOIN sys_role_menu r ON r.menu_id=m.id WHERE m.menu_key='TaskFeedMarketing'")) {
                int count = 0;
                while (rows.next()) {
                    assertThat(rows.getLong("parent_id")).isZero();
                    assertThat(rows.getInt("sort_no")).isBetween(31, 39);
                    assertThat(rows.getLong("child_parent")).isEqualTo(rows.getLong("id"));
                    assertThat(rows.getLong("menu_id")).isEqualTo(rows.getLong("id"));
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }
}
