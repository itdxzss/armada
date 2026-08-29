package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** H1 动态菜单只创建列表页与 create/edit/action/export 四类按钮权限。 */
class HyperlinkTaskListMenuMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V160__hyperlink_task_list_menu_rbac.sql");

    @Test
    void migrationAddsListRouteAndH1PermissionsWithoutDeleteOrFutureFeaturePermissions()
            throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains(
                        "'超链任务', 'HyperlinkTaskList', 'M'",
                        "'/hyperlink/tasks', 'hyperlink/task/index'",
                        "tenant:hyperlink_task:view",
                        "tenant:hyperlink_task:create",
                        "tenant:hyperlink_task:edit",
                        "tenant:hyperlink_task:action",
                        "tenant:hyperlink_task:export")
                .doesNotContain(
                        "tenant:hyperlink_task:delete",
                        "tenant:hyperlink_task:attribution_sensitive",
                        "INSERT INTO sys_role_menu",
                        "INSERT IGNORE INTO sys_role_menu");
    }
}
