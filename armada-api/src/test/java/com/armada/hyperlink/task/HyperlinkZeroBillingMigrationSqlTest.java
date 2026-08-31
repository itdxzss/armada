package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 零计费业务启用所需持久审计表的 Flyway 结构合同。 */
class HyperlinkZeroBillingMigrationSqlTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V171__hyperlink_task_audit.sql");

    @Test
    void createsTenantScopedIdempotentAuditFacts() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS hyperlink_task_audit_event",
                "tenant_id BIGINT NOT NULL",
                "event_id VARCHAR(191) NOT NULL",
                "UNIQUE KEY uq_hyperlink_task_audit_event (tenant_id, event_id)",
                "hyperlink_task_id BIGINT NOT NULL",
                "occurred_at BIGINT NOT NULL");
    }
}
