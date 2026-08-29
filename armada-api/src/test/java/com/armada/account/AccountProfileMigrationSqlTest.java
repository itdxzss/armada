package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 共享账号画像表字段、约束与筛选索引的 Flyway 结构合同。 */
class AccountProfileMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V158__account_profile.sql");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");

    @Test
    void createsOnlyTheApprovedSharedProfileTableWithFrozenFields() throws IOException {
        String sql = sql();
        Matcher matcher = CREATE_TABLE.matcher(sql);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualToIgnoringCase("account_profile");
        assertThat(matcher.find()).isFalse();
        assertThat(sql).contains(
                "friend_count INT DEFAULT NULL",
                "friend_count_synced_at BIGINT DEFAULT NULL",
                "is_group_invite_allowed TINYINT(1) DEFAULT NULL",
                "group_invite_synced_at BIGINT DEFAULT NULL",
                "rotation_status TINYINT DEFAULT NULL",
                "rotation_updated_at BIGINT DEFAULT NULL",
                "registered_at BIGINT DEFAULT NULL",
                "registered_at_source TINYINT DEFAULT NULL",
                "marketing_source TINYINT DEFAULT NULL",
                "marketing_source_updated_at BIGINT DEFAULT NULL",
                "created_at BIGINT NOT NULL",
                "updated_at BIGINT NOT NULL");
    }

    @Test
    void definesFrozenUniquenessLookupIndexesAndValueChecks() throws IOException {
        assertThat(sql()).contains(
                "UNIQUE KEY uq_account_profile (tenant_id, account_id)",
                "KEY idx_account_profile_friend (tenant_id, friend_count)",
                "KEY idx_account_profile_friend_sync (tenant_id, friend_count_synced_at, account_id)",
                "KEY idx_account_profile_invite (tenant_id, is_group_invite_allowed, account_id)",
                "KEY idx_account_profile_invite_sync (tenant_id, group_invite_synced_at, account_id)",
                "KEY idx_account_profile_rotation (tenant_id, rotation_status, account_id)",
                "KEY idx_account_profile_registered (tenant_id, registered_at, account_id)",
                "KEY idx_account_profile_source (tenant_id, marketing_source, account_id)",
                "CHECK (friend_count IS NULL OR friend_count >= 0)",
                "CHECK (is_group_invite_allowed IS NULL OR is_group_invite_allowed IN (0, 1))",
                "CHECK (rotation_status IS NULL OR rotation_status IN (0, 1, 2, 3))",
                "CHECK (registered_at_source IS NULL OR registered_at_source IN (1, 2, 3))",
                "CHECK (marketing_source IS NULL OR marketing_source IN (0, 1, 2, 3, 4))",
                "idx_account_hyperlink_platform");
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
