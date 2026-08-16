package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群链接邀请权限双快照迁移契约测试。 */
class GroupMemberLinkModeMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V124__group_member_link_mode.sql");

    @Test
    void migrationAddsIndependentModeToLegacyAndCurrentSnapshots() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE group_link_preview", "ALTER TABLE wa_group_profile")
                .contains("member_link_mode")
                .contains("ck_wa_group_profile_member_link");
    }
}
