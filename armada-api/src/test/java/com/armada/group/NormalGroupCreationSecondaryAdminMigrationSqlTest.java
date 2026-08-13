package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 次管理员任务快照与提权状态迁移的结构测试。 */
class NormalGroupCreationSecondaryAdminMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V113__normal_group_creation_secondary_admin.sql");

    @Test
    void migrationAddsTaskConfigurationAndSecondarySnapshot() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("secondary_admin_account_group_id")
                .contains("secondary_admin_count")
                .contains("CREATE TABLE IF NOT EXISTS normal_group_creation_item_secondary_admin")
                .contains("anchor_member_account_id")
                .contains("creator_saved_secondary_status")
                .contains("secondary_saved_creator_status")
                .contains("secondary_saved_anchor_status")
                .contains("anchor_saved_secondary_status")
                .contains("promotion_status")
                .contains("uq_normal_group_creation_secondary_admin")
                .doesNotContain("admin_promotion_command_id")
                .doesNotContain("admin_promotion_status");
    }
}
