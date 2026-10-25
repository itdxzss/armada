package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉群角色账号按任务索引 V218 迁移脚本的结构契约测试。 */
class PullTaskGroupAccountTaskIndexMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V218__pull_task_group_account_task_index.sql");

    @Test
    void migrationAddsTaskRoleIndexIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 任务列表按 task_id + 角色 + 可用性统计可用拉手，现有索引都不以 task_id 跟在 tenant_id 后。
        assertThat(sql)
                .contains("index_name = 'idx_pull_task_group_account_task'")
                .contains("(tenant_id, task_id, role_type, availability_status)");
    }

    @Test
    void migrationOnlyAddsIndexesAndTouchesNoBusinessRows() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.statistics")
                .doesNotContainIgnoringCase("UPDATE ")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("INSERT INTO")
                .doesNotContainIgnoringCase("DROP ")
                .doesNotContainIgnoringCase("ADD COLUMN");
    }
}
