package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 逐号码执行台账 V106 迁移脚本的结构契约测试。 */
class PullTaskParticipantAttemptMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V106__pull_task_participant_attempt.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesAttemptLedgerWithIdentityFactsAndTimestamps() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE pull_task_pull_call_member_attempt")
                .contains("participant_type TINYINT NOT NULL")
                .contains("participant_ref_id BIGINT NOT NULL")
                .contains("target_phone VARCHAR(32)")
                .contains("target_jid VARCHAR(128)")
                .contains("puller_group_account_id BIGINT NOT NULL")
                .contains("attempt_no INT NOT NULL")
                .contains("failure_count_before BIGINT NOT NULL DEFAULT 0")
                .contains("lifecycle_status TINYINT NOT NULL DEFAULT 1")
                .contains("active_slot TINYINT DEFAULT 1")
                .contains("protocol_outcome VARCHAR(16)")
                .contains("execution_state VARCHAR(16)")
                .contains("submitted_at BIGINT")
                .contains("result_at BIGINT")
                .contains("released_at BIGINT")
                .contains("created_at BIGINT NOT NULL")
                .contains("updated_at BIGINT NOT NULL");
    }

    @Test
    void migrationAddsAggregateAndRosterCheckColumnsWithDefaults() throws IOException {
        assertThat(sql())
                .contains("ALTER TABLE pull_task_material_member")
                .contains("pull_failure_count BIGINT NOT NULL DEFAULT 0")
                .contains("active_pull_attempt_id BIGINT DEFAULT NULL")
                .contains("last_puller_group_account_id BIGINT DEFAULT NULL")
                .contains("ALTER TABLE pull_task_group_account")
                .contains("membership_failure_count BIGINT NOT NULL DEFAULT 0")
                .contains("ALTER TABLE pull_task_pull_call")
                .contains("roster_check_status TINYINT NOT NULL DEFAULT 0")
                .contains("roster_check_started_at BIGINT DEFAULT NULL")
                .contains("roster_check_finished_at BIGINT DEFAULT NULL");
    }

    @Test
    void migrationDefinesActiveCallbackAndSchedulingIndexesWithoutDataBackfill() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_pull_task_attempt_call_participant")
                .contains("UNIQUE KEY uq_pull_task_attempt_active")
                .contains("UNIQUE KEY uq_pull_task_attempt_sequence")
                .contains("KEY idx_pull_task_attempt_callback")
                .contains("KEY idx_pull_task_attempt_schedule")
                .doesNotContainIgnoringCase("INSERT INTO pull_task_pull_call_member_attempt")
                .doesNotContainIgnoringCase("UPDATE pull_task_material_member")
                .doesNotContainIgnoringCase("UPDATE pull_task_group_account");
    }
}
