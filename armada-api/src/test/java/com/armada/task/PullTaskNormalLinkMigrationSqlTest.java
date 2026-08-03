package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 普通群链接执行域 Flyway 脚本契约测试。
 *
 * <p>列级排序规则和 MySQL 专有的生成列写法无法在 H2 MySQL 模式下验证
 * （H2 不支持列级 CHARACTER SET / COLLATE，且默认大小写敏感，会让
 * utf8mb4_0900_ai_ci 造成的"仅大小写不同即判重复"问题静默通过），
 * 因此改为对脚本文本做结构断言。</p>
 */
class PullTaskNormalLinkMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V090__pull_task_normal_link_execution.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesSixExecutionTables() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE IF NOT EXISTS pull_task_standard_setting")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_execution")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_material_member")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_group_account")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_account_action")
                .contains("CREATE TABLE IF NOT EXISTS pull_task_pull_call");
    }

    @Test
    void migrationAddsPullTaskLifecycleColumnsIdempotently() throws IOException {
        String sql = sql();
        assertThat(sql)
                .contains("information_schema.columns")
                .contains("column_name = 'started_at'")
                .contains("ADD COLUMN started_at BIGINT DEFAULT NULL")
                .contains("column_name = 'finished_at'")
                .contains("ADD COLUMN finished_at BIGINT DEFAULT NULL")
                .contains("column_name = 'version'")
                .contains("ADD COLUMN version INT NOT NULL DEFAULT 1");
    }

    @Test
    void exactMatchColumnsDeclareAsciiBinCollation() throws IOException {
        String sql = sql();
        // 表默认 utf8mb4_0900_ai_ci 大小写不敏感；WhatsApp 邀请码大小写敏感，
        // 漏声明会让仅大小写不同的两条链接被唯一键判为重复。
        assertThat(sql)
                .contains("normalized_link VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("invite_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("group_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("normalized_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("account_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("wa_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("lock_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin");
    }

    @Test
    void generatedColumnsUseNullElseBranch() throws IOException {
        String sql = sql();
        // else 分支写成 0 会让唯一索引把已释放记录也纳入约束，
        // 导致一个账号一生只能有一条释放记录。
        assertThat(sql)
                .contains("CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END")
                .contains("CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END");
        assertThat(sql).doesNotContain("ELSE 0 END");
    }

    @Test
    void schedulerIndexHasNoTenantPrefix() throws IOException {
        // 后台调度器无租户上下文（MyBatisConfig fail-closed 回退 -1），
        // 必须有一条不以 tenant_id 打头的索引供跨租户扫描。
        assertThat(sql())
                .contains("KEY idx_pull_task_execution_dispatch "
                        + "(execution_status, manual_paused, next_run_at, id)");
    }

    @Test
    void callbackLookupIndexesExist() throws IOException {
        // 三张回调定位索引都必须是 UNIQUE：selectByAdminCommandId/selectByCommandId
        // 返回单行，若命令 ID 重复，MyBatis 会抛 TooManyResultsException，
        // 协议回调将永久无法处理。
        assertThat(sql())
                .contains("UNIQUE KEY uq_pull_task_action_command (tenant_id, command_id)")
                .contains("UNIQUE KEY uq_pull_task_call_command (tenant_id, command_id)")
                .contains("UNIQUE KEY uq_pull_task_material_admin_command "
                        + "(tenant_id, admin_command_id)");
    }

    @Test
    void occupancyUniqueKeysExist() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_pull_task_execution_link_occupancy "
                        + "(tenant_id, link_occupancy_key)")
                .contains("UNIQUE KEY uq_pull_task_group_account_occupancy "
                        + "(tenant_id, occupancy_key)");
    }
}
