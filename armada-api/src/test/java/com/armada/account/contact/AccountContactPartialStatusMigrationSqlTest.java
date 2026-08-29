package com.armada.account.contact;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V161 同步状态取值扩充迁移的 SQL 文本契约测试。本机无库，只校验脚本。 */
class AccountContactPartialStatusMigrationSqlTest {

    private static String sql() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/db/migration/V161__account_contact_partial_status.sql"),
                StandardCharsets.UTF_8);
    }

    @Test
    void documentsPartialStatus() throws IOException {
        String text = sql();

        assertThat(text).contains("account_contact_sync");
        assertThat(text).contains("sync_status");
        assertThat(text).contains("PARTIAL");
    }

    @Test
    void keepsExistingStatusValuesInComment() throws IOException {
        String text = sql();

        assertThat(text).contains("NEVER");
        assertThat(text).contains("SYNCING");
        assertThat(text).contains("SUCCESS");
        assertThat(text).contains("FAILED");
    }

    @Test
    void doesNotChangeColumnType() throws IOException {
        // 只改注释，列宽和类型不动；改类型会锁表
        assertThat(sql()).contains("VARCHAR(16)");
    }
}
