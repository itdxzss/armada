package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 拉群执行行调度索引 V117 迁移脚本的结构契约测试。 */
class PullTaskDispatchIndexMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V117__pull_task_execution_dispatch_index.sql");

    @Test
    void migrationAddsTaskStatusIndexIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 并发槽位子查询按 (task_id, execution_status) 计数，现有 page 索引不含状态列，
        // 会退化成扫描该任务的全部执行行。
        assertThat(sql)
                .contains("index_name = 'idx_pull_task_execution_task_status'")
                .contains("idx_pull_task_execution_task_status")
                .contains("(tenant_id, task_id, execution_status)");
    }

    @Test
    void migrationAddsSchedulerLockIndexIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // selectClaimed 每个调度轮次执行一次，WHERE lock_owner = ? AND lock_expires_at > ?
        // 当前没有任何可用索引。
        assertThat(sql)
                .contains("index_name = 'idx_pull_task_execution_lock'")
                .contains("idx_pull_task_execution_lock")
                .contains("(lock_owner, lock_expires_at)");
    }

    @Test
    void migrationOnlyAddsIndexesAndTouchesNoBusinessRows() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 纯加索引：不得改列、不得动业务数据。
        assertThat(sql)
                .contains("information_schema.statistics")
                .doesNotContainIgnoringCase("UPDATE ")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("INSERT INTO")
                .doesNotContainIgnoringCase("DROP ")
                .doesNotContainIgnoringCase("ADD COLUMN");
    }
}
