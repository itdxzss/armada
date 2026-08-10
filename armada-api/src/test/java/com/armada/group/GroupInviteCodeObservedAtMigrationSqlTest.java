package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群当前邀请码独立观察时间 V109 迁移 SQL 契约测试。 */
class GroupInviteCodeObservedAtMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V109__group_invite_code_observed_at.sql");

    @Test
    void migrationAddsNullableInviteCodeObservationClock() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE group_link_preview")
                .contains("ADD COLUMN invite_code_observed_at BIGINT DEFAULT NULL")
                .contains("AFTER invite_code")
                .doesNotContain("NOT NULL");
    }
}
