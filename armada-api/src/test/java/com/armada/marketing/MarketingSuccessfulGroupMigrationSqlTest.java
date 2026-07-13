package com.armada.marketing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingSuccessfulGroupMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V051__marketing_task_success_group.sql");
    private static final Path ROLLBACK = Path.of(
            "../.harness/changes/marketing-task/rollback-v051.sql");

    @Test
    void migrationCreatesDedupFactsAndBackfillsOnlySuccessfulAttempts() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS marketing_task_success_group")
                .contains("UNIQUE KEY uq_marketing_task_success_group")
                .contains("(tenant_id, marketing_task_id, group_jid)")
                .contains("FROM marketing_task_send_attempt a")
                .contains("a.status = 1")
                .contains("TRIM(a.group_jid)")
                .contains("NOT EXISTS")
                .contains("FROM group_creation_marketing_item item")
                .contains("item.marketing_attempt_id = a.id")
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY a.tenant_id, a.marketing_task_id, TRIM(a.group_jid)")
                .contains("UPDATE marketing_task task")
                .contains("target_group_count = COALESCE(success.success_group_count, 0)")
                .contains("任务累计成功触达去重群数")
                .doesNotContain("DELETE FROM marketing_task_success_group");
    }

    @Test
    void featureRollbackRestoresOnlyFixedTargetCreationCount() throws IOException {
        assertThat(ROLLBACK).exists();
        String sql = Files.readString(ROLLBACK, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("target_scope = 1")
                .contains("COUNT(DISTINCT group_link_id)")
                .contains("DROP TABLE IF EXISTS marketing_task_success_group")
                .doesNotContain("DROP TABLE IF EXISTS marketing_task;")
                .doesNotContain("DROP TABLE IF EXISTS marketing_task_send_attempt;");
    }
}
