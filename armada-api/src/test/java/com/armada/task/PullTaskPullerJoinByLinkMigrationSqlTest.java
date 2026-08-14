package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉手踩链接进群配置 V116 迁移脚本的结构契约测试。 */
class PullTaskPullerJoinByLinkMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V116__pull_task_puller_join_by_link.sql");

    @Test
    void migrationAddsDisabledByDefaultBooleanFieldIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("column_name = 'is_puller_join_by_link'")
                .contains("is_puller_join_by_link TINYINT(1) NOT NULL DEFAULT 0")
                .doesNotContainIgnoringCase("UPDATE ", "DELETE FROM", "INSERT INTO");
    }
}
