package com.armada.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群组检测后延迟发送迁移脚本结构校验。 */
class MarketingNewGroupDelayMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V130__marketing_new_group_delay_send.sql");

    @Test
    void migrationAddsTaskConfigWaitingTimesAndDueIndex() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("is_new_group_delay_enabled")
                .contains("new_group_delay_value")
                .contains("new_group_delay_unit")
                .contains("detected_at")
                .contains("scheduled_send_at")
                .contains("outbox_accepted_at")
                .contains("JOIN protocol_command_outbox outbox")
                .contains("outbox.command_id = attempt.command_id")
                .contains("attempt.round_no > 0")
                .contains("4=等待发送")
                .contains("idx_marketing_attempt_wait_due")
                .contains("status, scheduled_send_at, id");
    }
}
