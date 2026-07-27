package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 空“群组管理”目录清理脚本约束测试。 */
class ObsoleteGroupManagementMenuMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V076__remove_obsolete_group_management_menu.sql");

    @Test
    void removesOnlyTheEmptyObsoleteDirectoryAndItsRoleBindings() throws Exception {
        String sql = Files.readString(MIGRATION);
        String normalized = sql.replaceAll("\\s+", " ").toUpperCase();

        assertThat(sql).contains("menu.menu_key = 'GroupManagement'");
        assertThat(normalized).contains("DELETE ROLE_MENU FROM SYS_ROLE_MENU ROLE_MENU");
        assertThat(normalized).contains("DELETE MENU FROM SYS_MENU MENU");
        assertThat(normalized).contains("LEFT JOIN SYS_MENU CHILD");
        assertThat(normalized).contains("AND CHILD.ID IS NULL");
        assertThat(sql).doesNotContain("AccountGroup", "TaskCenter");
    }
}
