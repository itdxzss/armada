package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PullTaskMemberQueryMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V108__pull_task_member_query.sql");

    @Test
    void migrationCreatesOneDurableQueryTableWithoutAttemptChildren() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS pull_task_member_query")
                .contains("target_jids_json")
                .contains("result_json")
                .contains("command_id")
                .contains("account_id")
                .contains("ws_phone")
                .contains("deadline_at")
                .doesNotContain("pull_task_member_query_attempt")
                .doesNotContain("pull_task_member_query_item");
    }

    @Test
    void migrationEnforcesOneOpenQueryPerBusinessKeyAndUniqueCommandCorrelation()
            throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("active_business_key")
                .contains("UNIQUE KEY uq_pull_task_member_query_open")
                .contains("UNIQUE KEY uq_pull_task_member_query_command")
                .contains("KEY idx_pull_task_member_query_execution");
    }
}
