package com.armada.hyperlink.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 超链策略 V168 表结构、菜单和 RBAC 的静态迁移合同。 */
class HyperlinkStrategyMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V168__hyperlink_strategy.sql");
    private static final Path MENU_SERVICE = Path.of(
            "src/main/java/com/armada/admin/service/impl/MenuManagementServiceImpl.java");

    @Test
    void migrationCreatesTenantScopedStrategyWithFrozenConstraints() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS hyperlink_strategy")
                .contains("tenant_id BIGINT NOT NULL")
                .contains("strategy_scope TINYINT NOT NULL")
                .contains("owner_task_id BIGINT DEFAULT NULL")
                .contains("source_strategy_id BIGINT DEFAULT NULL")
                .contains("strategy_name VARCHAR(128) DEFAULT NULL")
                .contains("task_type TINYINT NOT NULL")
                .contains("account_filter JSON NOT NULL")
                .contains("concurrent_num INT NOT NULL DEFAULT 10")
                .contains("max_use_account INT NOT NULL DEFAULT 0")
                .contains("account_max_send_num INT NOT NULL DEFAULT 0")
                .contains("task_interval_minutes INT NOT NULL DEFAULT 0")
                .contains("is_enabled TINYINT(1) NOT NULL DEFAULT 1")
                .contains("version INT NOT NULL DEFAULT 1")
                .contains("GENERATED ALWAYS AS")
                .contains("UNIQUE KEY uq_hyperlink_strategy_owner_task (tenant_id, owner_task_id)")
                .contains("CHECK (concurrent_num BETWEEN 0 AND 100)")
                .contains("max_use_account = 0 OR concurrent_num = 0")
                .contains("DROP COLUMN task_type")
                .contains("DROP COLUMN account_filter")
                .contains("DROP COLUMN concurrent_num")
                .contains("system_code VARCHAR(32)")
                .contains("account_group_row.owner_user_id IS NULL")
                .contains("HYPERLINK_PUBLIC", "HYPERLINK_MARKETING")
                .doesNotContain("account_send_concurrency", "msg_interval_min");
    }

    @Test
    void migrationAvoidsMysqlReservedGroupsAlias() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("UPDATE account_group AS account_group_row")
                .doesNotContain("UPDATE account_group groups", "groups.");
    }

    @Test
    void migrationCanResumeAfterAccountGroupSchemaWasPartiallyApplied() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("column_name = 'system_code'")
                .contains("information_schema.statistics")
                .contains("index_name = 'uq_account_group_system_code'")
                .contains("PREPARE hyperlink_group_schema_stmt")
                .contains("DEALLOCATE PREPARE hyperlink_group_schema_stmt");
    }

    @Test
    void migrationAddsSortFortyMenuAndFourPermissionsWithoutRoleAutoGrant() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("'超链策略', 'HyperlinkStrategy', 'M'")
                .contains("'/hyperlink/strategy', 'hyperlink/strategy/index'")
                .contains("'tenant:hyperlink_strategy:view'")
                .contains("'solar:tuning-2-bold-duotone', 40")
                .contains(
                        "tenant:hyperlink_strategy:create",
                        "tenant:hyperlink_strategy:edit",
                        "tenant:hyperlink_strategy:delete")
                .doesNotContain("INSERT INTO sys_role_menu", "INSERT IGNORE INTO sys_role_menu");
    }

    @Test
    void migratedPageComponentIsAcceptedByMenuManagementWhitelist() throws Exception {
        String service = Files.readString(MENU_SERVICE, StandardCharsets.UTF_8);

        assertThat(service).contains("\"hyperlink/strategy/index\"");
    }
}
