package com.armada.hyperlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 超链营销 V155 菜单、路由和按钮权限的 Flyway 结构合同测试。 */
class HyperlinkMenuMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V155__hyperlink_marketing_menu_rbac.sql");

    @Test
    void migrationSeedsDirectoryPagesAndAllPhaseOnePermissionsWithoutAutoGrantingRoles()
            throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("'超链营销', 'HyperlinkMarketing', 'D', '/hyperlink'")
                .contains(
                        "'超链数据包' AS menu_name",
                        "'HyperlinkDataPackage' AS menu_key",
                        "'/hyperlink/data' AS route_path",
                        "'hyperlink/data/index' AS component_path",
                        "'tenant:hyperlink_data:view' AS perm_key")
                .contains(
                        "'超链营销模板', 'HyperlinkTemplate', '/hyperlink/templates'",
                        "'hyperlink/templates/index', 'tenant:hyperlink_template:view'")
                .contains(
                        "tenant:hyperlink_data:create",
                        "tenant:hyperlink_data:import",
                        "tenant:hyperlink_data:edit",
                        "tenant:hyperlink_data:delete",
                        "tenant:hyperlink_template:create",
                        "tenant:hyperlink_template:edit",
                        "tenant:hyperlink_template:copy",
                        "tenant:hyperlink_template:delete")
                .contains("WHERE tenant.status = 1")
                .doesNotContain("INSERT INTO sys_role_menu", "INSERT IGNORE INTO sys_role_menu");
    }
}
