package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通拉人任务群组资源池迁移脚本结构测试。 */
class PullTaskGroupResourcePoolMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V138__pull_task_group_resource_pool.sql");

    @Test
    void migrationAddsOnlyTheRequiredFolderAndAttemptColumns() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("ADD COLUMN system_builtin TINYINT(1) NOT NULL DEFAULT 0")
                .contains("ADD COLUMN attempt_no INT NOT NULL DEFAULT 1")
                .doesNotContainIgnoringCase("CREATE TABLE");
    }

    @Test
    void migrationChangesExecutionUniquenessToAttemptAndGroupIdentity() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("uq_pull_task_execution_seq")
                .contains("tenant_id, task_id, seq, attempt_no")
                .contains("uq_pull_task_execution_file")
                .contains("tenant_id, task_id, source_file_index, attempt_no")
                .contains("CONCAT(''jid:'', group_jid)")
                .contains("CONCAT(''link:'', normalized_link)")
                .contains("UNIQUE KEY uq_pull_task_execution_link_occupancy ")
                .contains("(tenant_id, link_occupancy_key)");
    }

    @Test
    void migrationIsIdempotentAndDoesNotRewriteBusinessRows() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("information_schema.statistics")
                .contains("PREPARE")
                .contains("EXECUTE")
                .contains("DEALLOCATE PREPARE")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("TRUNCATE");
    }

    private static String readMigration() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
