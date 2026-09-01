package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉手专用限制字段、索引和存量事实回填的 Flyway 结构合同。 */
class AccountPullerRestrictionMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V173__account_operation_restriction.sql");
    private static final Path RETRY_MIGRATION = Path.of(
            "src/main/resources/db/migration/V174__hyperlink_recipient_dispatch_attempt.sql");
    private static final Path USAGE_STATUS_MIGRATION = Path.of(
            "src/main/resources/db/migration/V175__hyperlink_account_usage_operation_restricted.sql");

    @Test
    void reusesUnifiedStateAndBackfillsStandardRestrictionFacts() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "mute_status",
                "cooldown_until",
                "restriction_reason_code",
                "restriction_reported_at",
                "idx_account_state_restriction_due",
                "RATE_LIMITED",
                "ACCOUNT_REACHOUT_RESTRICTED",
                "pull_task_pull_call_member_attempt",
                "pull_task_material_member",
                "pull_task_group_account",
                "86400000");
        assertThat(sql).doesNotContain(
                "ADD COLUMN puller_restriction_status",
                "ADD COLUMN puller_restriction_until");
    }

    @Test
    void addsASeparateDispatchAttemptForAccountSwitchRetries() throws IOException {
        String sql = Files.readString(RETRY_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "hyperlink_task_recipient",
                "dispatch_attempt",
                "DEFAULT 1");
    }

    @Test
    void allowsOperationRestrictedTaskAccountUsageStatus() throws IOException {
        String sql = Files.readString(USAGE_STATUS_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "DROP CHECK ck_hyperlink_usage_status",
                "usage_status IN (1,2,3,4,5,6)",
                "6操作受限");
    }
}
