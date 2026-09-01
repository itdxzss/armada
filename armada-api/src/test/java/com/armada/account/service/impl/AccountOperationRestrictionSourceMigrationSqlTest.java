package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 锁定账号操作限制从单一截止时间扩展为可独立解除的分源投影。 */
class AccountOperationRestrictionSourceMigrationSqlTest {

    @Test
    void migrationAddsSourceDeadlinesAndBackfillsExistingCapabilities() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V177__account_operation_restriction_sources.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "information_schema.COLUMNS",
                    "information_schema.STATISTICS",
                    "fallback_message_restriction_until BIGINT",
                    "platform_message_restriction_until BIGINT",
                    "platform_message_restriction_active TINYINT",
                    "platform_message_restriction_reported_at BIGINT",
                    "pulling_restriction_until BIGINT",
                    "WHEN mute_status IN (1, 3) THEN cooldown_until",
                    "WHEN mute_status IN (2, 3) THEN cooldown_until",
                    "idx_account_state_message_restriction_due",
                    "idx_account_state_pulling_restriction_due");
        }
    }
}
