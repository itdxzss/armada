package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 最近一次精确退群事实 Flyway 脚本契约测试。 */
class AccountGroupMembershipLastExitMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V083_1__account_group_membership_last_exit.sql");

    @Test
    void migrationAddsIdempotentExitColumnsAndBackfillsKnownExitStates() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("column_name = 'last_exit_type'")
                .contains("ADD COLUMN last_exit_type TINYINT NULL")
                .contains("column_name = 'last_exited_at'")
                .contains("ADD COLUMN last_exited_at BIGINT NULL")
                .contains("SET last_exit_type = COALESCE(last_exit_type, membership_status)")
                .contains("last_exited_at = COALESCE(last_exited_at, status_updated_at)")
                .contains("WHERE membership_status IN (3, 4)");
    }
}
