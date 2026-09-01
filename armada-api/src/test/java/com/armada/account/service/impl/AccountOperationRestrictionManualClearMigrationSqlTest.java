package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 锁定业务风控人工解除水位的可重跑 Flyway 结构。 */
class AccountOperationRestrictionManualClearMigrationSqlTest {

    @Test
    void migrationAddsGuardedManualClearWatermark() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V178__account_operation_restriction_manual_clear.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "information_schema.COLUMNS",
                    "manual_restriction_cleared_at BIGINT",
                    "COLUMN_NAME='manual_restriction_cleared_at'");
        }
    }
}
