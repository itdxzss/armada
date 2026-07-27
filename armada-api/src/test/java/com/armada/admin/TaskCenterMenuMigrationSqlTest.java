package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TaskCenterMenuMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V075__restore_task_center_menu_structure.sql");

    @Test
    void restoresEveryOriginalTaskCenterMenuWithoutPhysicalDelete() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains(
                "WHEN 'AccountImport' THEN 10",
                "WHEN 'TaskGroupLinkImports' THEN 20",
                "WHEN 'GroupList' THEN 30",
                "WHEN 'HistoricalGroupManagement' THEN 40",
                "WHEN 'TaskPull' THEN 50",
                "WHEN 'TaskJoin' THEN 60",
                "WHEN 'TaskGroupMarketing' THEN 70",
                "WHEN 'TaskGroupPullMarketing' THEN 80",
                "WHEN 'TaskGroupCreationMarketing' THEN 90");
        assertThat(sql).contains("SET child.parent_id = task_center.id", "task/group-pull-marketing/index");
        assertThat(sql).contains("WHERE menu_key = 'GroupManagement'");
        assertThat(sql).contains("SET menu_name = '运营管理'", "WHERE menu_key = 'ResourceManagement'");
        assertThat(sql).contains(
                "SET perm_key = 'tenant:group_link_import:view'",
                "WHERE menu_key = 'TaskGroupLinkImports'");
        assertThat(sql.toUpperCase()).doesNotContain("DELETE FROM SYS_MENU");
    }
}
