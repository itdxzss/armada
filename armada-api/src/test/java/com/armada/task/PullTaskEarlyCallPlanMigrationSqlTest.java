package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通群链接前期拉人调用计划 V115 迁移脚本的结构契约测试。 */
class PullTaskEarlyCallPlanMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V115__pull_task_early_call_plan.sql");

    @Test
    void migrationAddsBothPlanFieldsIdempotentlyWithoutChangingExistingTasks()
            throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("column_name = 'early_pull_count'")
                .contains("early_pull_count INT NOT NULL DEFAULT 1")
                .contains("column_name = 'early_pull_call_count'")
                .contains("early_pull_call_count INT NOT NULL DEFAULT 0")
                .doesNotContainIgnoringCase("UPDATE ", "DELETE FROM", "INSERT INTO");
    }
}
