package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通群链接拉人波次 V107 迁移脚本的结构契约测试。 */
class PullTaskPullWaveMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V107__pull_task_pull_wave.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesWaveAndAssignmentIdentityWithoutDataBackfill() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE IF NOT EXISTS pull_task_pull_wave")
                .contains("active_pull_wave_id BIGINT DEFAULT NULL")
                .contains("active_puller_group_account_id BIGINT DEFAULT NULL")
                .contains("puller_assignment_seq BIGINT NOT NULL DEFAULT 0")
                .contains("pull_wave_id BIGINT DEFAULT NULL")
                .contains("wave_call_seq INT DEFAULT NULL")
                .doesNotContainIgnoringCase("INSERT INTO", "UPDATE ", "DELETE FROM");
    }

    @Test
    void migrationGuardsIncrementalColumnsAndIndexes() throws IOException {
        assertThat(sql())
                .contains("information_schema.columns")
                .contains("information_schema.statistics")
                .contains("column_name = 'active_pull_wave_id'")
                .contains("column_name = 'pull_wave_id'")
                .contains("index_name = 'uq_pull_task_call_wave_seq'")
                .contains("index_name = 'idx_pull_task_attempt_wave'");
    }
}
