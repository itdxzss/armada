package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群快照命令关联 V129 迁移脚本的结构契约测试。 */
class GroupSnapshotCommandCorrelationMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V129__group_snapshot_command_correlation.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void metadataTaskCarriesOnlyCommandScopesAndCandidateCorrelation() throws IOException {
        assertThat(sql())
                .contains("ALTER TABLE group_metadata_sync_task")
                .contains("current_command_id VARCHAR(64) DEFAULT NULL")
                .contains("requested_scope_mask TINYINT NOT NULL DEFAULT 0")
                .contains("completed_scope_mask TINYINT NOT NULL DEFAULT 0")
                .contains("candidate_cursor INT NOT NULL DEFAULT 0")
                .contains("result_deadline_at BIGINT DEFAULT NULL")
                .contains("idx_gmst_command (tenant_id, current_command_id)")
                .contains("idx_gmst_deadline (tenant_id, result_deadline_at)");
        assertThat(sql()).doesNotContain("peer_task_id", "WAITING_PEER");
    }

    @Test
    void batchItemCarriesAsynchronousCommandCorrelation() throws IOException {
        assertThat(sql())
                .contains("ALTER TABLE group_batch_task_item")
                .contains("current_command_id VARCHAR(64) DEFAULT NULL")
                .contains("attempt_count INT NOT NULL DEFAULT 0")
                .contains("candidate_cursor INT NOT NULL DEFAULT 0")
                .contains("result_deadline_at BIGINT DEFAULT NULL")
                .contains("completed_scope_mask TINYINT NOT NULL DEFAULT 0")
                .contains("idx_gbti_command (tenant_id, current_command_id)")
                .contains("idx_gbti_deadline (tenant_id, result_deadline_at)");
    }

    @Test
    void everyAddedColumnAndIndexIsGuardedForSharedDatabaseRecovery() throws IOException {
        assertThat(sql())
                .contains("information_schema.columns")
                .contains("information_schema.statistics")
                .contains("table_schema = DATABASE()")
                .contains("PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;");
    }

    @Test
    void migrationOnlyChangesTaskProcessAggregatesAndDoesNotBackfillFacts() throws IOException {
        assertThat(sql())
                .doesNotContain("ALTER TABLE group_link")
                .doesNotContain("ALTER TABLE wa_group_profile")
                .doesNotContainIgnoringCase("UPDATE ", "DELETE FROM", "INSERT INTO");
    }
}
