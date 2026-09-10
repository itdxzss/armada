package com.armada.marketing.script;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ScriptMarketingMigrationTest {
    @Test
    void eachGroupStepHasOneDurableSendFact() throws Exception {
        var resource = new ClassPathResource("db/migration/V180__script_marketing.sql");
        assertThat(resource.exists()).as("independent script marketing migration").isTrue();
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("script_marketing_task", "script_marketing_group",
                "script_marketing_send_record", "UNIQUE KEY uk_script_group_step (tenant_id, group_id, step_index)",
                "UNIQUE KEY uk_script_command (command_id)");
        assertThat(sql).doesNotContain("ALTER TABLE marketing_task");
    }
    @Test
    void migrationCreatesIndependentMenuAndPreservesExistingMarketingMenu() throws Exception {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:script_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE sys_menu (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT,
                parent_id BIGINT, menu_name VARCHAR(100), menu_key VARCHAR(100), menu_type VARCHAR(1),
                route_path VARCHAR(255), component_path VARCHAR(255), perm_key VARCHAR(100),
                icon VARCHAR(100), sort_no INT, status INT, created_at BIGINT, updated_at BIGINT,
                UNIQUE(tenant_id, menu_key))
                """);
        jdbc.execute("INSERT INTO sys_menu(tenant_id, menu_key, created_at, updated_at) VALUES (7, 'TaskCenter', 1, 1)");
        jdbc.execute("INSERT INTO sys_menu(tenant_id, menu_key, component_path) VALUES (7, 'TaskGroupMarketing', 'task/group-marketing/index')");
        String sql = new ClassPathResource("db/migration/V180__script_marketing.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT component_path FROM sys_menu WHERE menu_key='TaskGroupMarketing'", String.class))
                .isEqualTo("task/group-marketing/index");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perm_key LIKE 'tenant:script_marketing:%'", Long.class)).isEqualTo(4);

        Long originalTaskMenuId = jdbc.queryForObject("SELECT id FROM sys_menu WHERE menu_key='TaskScriptMarketing'", Long.class);
        String upgrade = new ClassPathResource("db/migration/V187__script_definition_library.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String statement : upgrade.split(";")) {
            if (statement.isBlank()) continue;
            if (statement.stripLeading().startsWith("UPDATE sys_menu task_menu")) {
                // H2 不支持 MySQL 的 UPDATE JOIN：先解析原 SQL，再只适配该语法执行菜单数据验证。
                assertThat(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(statement))
                        .isInstanceOf(net.sf.jsqlparser.statement.update.Update.class);
                statement = statement.replace("JOIN sys_menu directory ON directory.tenant_id=task_menu.tenant_id AND directory.menu_key='GroupMaintenance'", "")
                        .replace("SET task_menu.parent_id=directory.id", "SET task_menu.parent_id=(SELECT directory.id FROM sys_menu directory WHERE directory.tenant_id=task_menu.tenant_id AND directory.menu_key='GroupMaintenance')");
            }
            jdbc.execute(statement);
        }
        Long directory = jdbc.queryForObject("SELECT id FROM sys_menu WHERE menu_key='GroupMaintenance'", Long.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE parent_id=?", Long.class, directory)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, originalTaskMenuId)).isEqualTo(directory);
        assertThat(jdbc.queryForObject("SELECT route_path FROM sys_menu WHERE id=?", String.class, originalTaskMenuId)).isEqualTo("/task/script-marketing");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE parent_id=? AND menu_type='B'", Long.class, originalTaskMenuId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT component_path FROM sys_menu WHERE menu_key='ScriptDefinitionLibrary'", String.class))
                .isEqualTo("task/script-definition/index");
    }
}
