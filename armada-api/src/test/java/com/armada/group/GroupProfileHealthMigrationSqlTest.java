package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 已解析群的健康事实必须落在群资料，不依赖是否存在邀请码。 */
class GroupProfileHealthMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V121__group_profile_health.sql");
    private static final Path MEMBER_COUNT_MIGRATION = Path.of(
            "src/main/resources/db/migration/V122__group_profile_checked_member_count.sql");

    @Test
    void migrationAddsOnlyTheMissingProfileHealthColumns() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE wa_group_profile")
                .contains("health_status TINYINT DEFAULT NULL")
                .contains("banned TINYINT DEFAULT NULL")
                .contains("last_checked_at BIGINT DEFAULT NULL")
                .contains("last_error_code VARCHAR(64) DEFAULT NULL")
                .contains("failure_count INT NOT NULL DEFAULT 0")
                .contains("idx_wa_group_profile_health")
                .doesNotContainIgnoringCase(
                        "CREATE TABLE", "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP TABLE");
    }

    @Test
    void migrationSeparatesHealthCheckedCountFromProfileMemberCount() throws IOException {
        String sql = Files.readString(MEMBER_COUNT_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE wa_group_profile")
                .contains("checked_member_count INT DEFAULT NULL")
                .contains("ck_wa_group_profile_checked_member_count")
                .doesNotContainIgnoringCase(
                        "CREATE TABLE", "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP TABLE");
    }
}
