package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 通讯录营销菜单与权限 Flyway 脚本契约测试。 */
class ContactMenuRbacMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V164__contact_marketing_menu_rbac.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsDirectoryAndTwoPages() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("'通讯录营销'")
                .contains("'ContactMarketing'")
                .contains("'/contact'")
                .contains("'通讯录超链任务'")
                .contains("'/contact/hyperlink'")
                .contains("'通讯录剧本任务'")
                .contains("'/contact/script'");
    }

    @Test
    void declaresFourPermissionKeys() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("tenant:contact_task:view")
                .contains("tenant:contact_task:create")
                .contains("tenant:contact_task:edit")
                .contains("tenant:contact_task:operate");
    }

    @Test
    void declaresNoDeletePermission() throws IOException {
        // 竞品没有删除任务的能力，权限节点也不该有
        assertThat(sql()).doesNotContain("tenant:contact_task:delete");
    }

    @Test
    void insertsAreIdempotent() throws IOException {
        // 与 V155 同一策略：INSERT IGNORE，重复执行不炸
        assertThat(sql()).contains("INSERT IGNORE INTO sys_menu");
    }

    @Test
    void doesNotAutoGrantToOrdinaryRoles() throws IOException {
        // V155 的既有结论：迁移只建节点，授权由管理员显式配置
        assertThat(sql()).doesNotContain("INSERT INTO sys_role_menu");
    }
}
