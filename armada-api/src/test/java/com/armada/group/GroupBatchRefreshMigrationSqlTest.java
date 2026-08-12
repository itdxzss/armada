package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群组列表批量刷新任务 V112 迁移脚本的结构契约测试。 */
class GroupBatchRefreshMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V112__group_batch_refresh_task.sql");

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesBatchTaskAggregateWithoutTouchingExistingData() throws IOException {
        assertThat(sql())
                .contains("CREATE TABLE IF NOT EXISTS group_batch_task")
                .contains("CREATE TABLE IF NOT EXISTS group_batch_task_item")
                .contains("tenant_id BIGINT NOT NULL")
                .contains("task_type TINYINT NOT NULL")
                .contains("total_count INT NOT NULL")
                .contains("success_count INT NOT NULL")
                .contains("failed_count INT NOT NULL")
                .doesNotContainIgnoringCase("INSERT INTO", "UPDATE ", "DELETE FROM");
    }

    @Test
    void batchTaskEnforcesRequestIdIdempotencyAndPerGroupDeduplication() throws IOException {
        assertThat(sql())
                // BR-08：同一租户同一 requestId 只允许一个任务，重复提交直接命中唯一键。
                .contains("uq_group_batch_task_request (tenant_id, request_id)")
                // BR-02：一个任务内同一群只处理一次，落地提交时的去重要求。
                .contains("uq_group_batch_task_item_group (task_id, group_link_id)");
    }

    @Test
    void batchTaskItemKeepsBaselineForObservingMetadataSyncCompletion() throws IOException {
        // 批量获取最新群信息复用耐久队列，靠提交时冻结的 last_success_at 基线判定该项是否已刷新。
        assertThat(sql()).contains("baseline_synced_at BIGINT DEFAULT NULL");
    }

    @Test
    void statusColumnsDocumentEveryBusinessCodeInline() throws IOException {
        assertThat(sql())
                .contains("1=待执行 2=运行中 3=已完成 4=任务失败")
                .contains("1=刷新群链接 2=获取最新群信息")
                .contains("1=待执行 2=成功 3=失败");
    }
}
