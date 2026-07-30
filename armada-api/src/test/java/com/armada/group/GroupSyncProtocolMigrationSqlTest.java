package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群组同步来源位掩码迁移脚本结构测试。 */
class GroupSyncProtocolMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V082__group_sync_protocol_mask.sql");

    @Test
    void migrationAddsMaskAndBackfillsExistingObservedProtocols() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN sync_protocol_mask TINYINT NOT NULL DEFAULT 0")
                .contains("account_group_membership")
                .contains("protocol_id")
                .contains("BIT_OR")
                .contains("UPPER(TRIM(account.protocol_id)) = 'ANDROID'");
    }
}
