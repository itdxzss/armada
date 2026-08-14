package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群管理员事实修复上线后，一次性唤醒受影响拉群执行的 SQL 合同测试。 */
class GroupAdminEventAndPullFallbackMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V114__group_admin_event_and_pull_fallback.sql");

    @Test
    void wakesOnlyActiveNormalLinkExecutionsBlockedByMissingAdminActor() throws IOException {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("UPDATE pull_task_group_execution execution_row")
                .contains("JOIN pull_task task_row")
                .contains("execution_row.execution_status = 2")
                .contains("execution_row.wait_resource_type = NULL")
                .contains("execution_row.reason_code = NULL")
                .contains("execution_row.reason_message = NULL")
                .contains("execution_row.next_run_at = 0")
                .contains("execution_row.lock_owner = NULL")
                .contains("execution_row.lock_expires_at = NULL")
                .contains("execution_row.version = execution_row.version + 1")
                .contains("execution_row.execution_status = 3")
                .contains("execution_row.stage = 3")
                .contains("execution_row.wait_resource_type = 1")
                .contains("execution_row.reason_code = 'MANAGER_ADMIN_ACTOR_UNAVAILABLE'")
                .contains("execution_row.manual_paused = 0")
                .contains("task_row.task_type = 'STANDARD'")
                .contains("task_row.mode = 'NORMAL_LINK'")
                .contains("task_row.status = 'EXECUTING'")
                .contains("task_row.deleted_at IS NULL");
    }

    @Test
    void doesNotBackfillMembershipOrCreatePeriodicMetadataWork() throws IOException {
        assertThat(normalizedSql())
                .doesNotContain("account_group_membership")
                .doesNotContain("protocol_group_metadata_sync_state")
                .doesNotContain("INSERT INTO")
                .doesNotContain("CREATE EVENT");
    }

    private static String normalizedSql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }
}
