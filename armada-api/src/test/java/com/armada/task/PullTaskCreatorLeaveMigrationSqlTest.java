package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PullTaskCreatorLeaveMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V137__pull_task_creator_leave.sql");

    @Test
    void migrationAddsTaskSwitchAndMinimalExecutionResultWithGuards() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("column_name = 'is_creator_leave_after_pull'")
                .contains("ADD COLUMN is_creator_leave_after_pull TINYINT(1) NOT NULL DEFAULT 0")
                .contains("column_name = 'creator_leave_result'")
                .contains("ADD COLUMN creator_leave_result TINYINT NOT NULL DEFAULT 0")
                .contains("column_name = 'creator_leave_reason'")
                .contains("ADD COLUMN creator_leave_reason VARCHAR(255) DEFAULT NULL")
                .doesNotContainIgnoringCase("CREATE TABLE")
                .doesNotContain("used_group_folder");
    }
}
