package com.armada.platform.protocol.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtocolRiskEventMigrationSqlTest {

    @Test
    void migrationCreatesAppendOnlyScopedRiskFactsWithAnalysisIndexes() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V176__protocol_risk_event.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "CREATE TABLE IF NOT EXISTS protocol_risk_event",
                    "UNIQUE KEY uq_protocol_risk_event (tenant_id, event_id)",
                    "signal_code VARCHAR(64) NOT NULL",
                    "scope_type VARCHAR(16) NOT NULL",
                    "operation_type VARCHAR(64)",
                    "group_business_id BIGINT",
                    "restricted_until BIGINT",
                    "idx_protocol_risk_signal",
                    "idx_protocol_risk_account",
                    "idx_protocol_risk_business",
                    "idx_protocol_risk_chat");
            assertThat(sql).doesNotContain("UPDATE protocol_risk_event", "deleted_at");
        }
    }
}
