package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** V089 拉群营销单群占用表结构测试。 */
class PullTaskGroupMarketingOccupancyMigrationTest {

    @Test
    void definesTenantScopedSingleActiveOccupancyAndOwnerIndexes() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V089__pull_task_group_marketing_group_occupancy.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("tenant_id BIGINT NOT NULL")
                .contains("group_jid VARCHAR(128) NOT NULL")
                .contains("group_source VARCHAR(16) NOT NULL")
                .contains("expires_at BIGINT DEFAULT NULL")
                .contains("CASE WHEN released_at IS NULL THEN 1 ELSE NULL END")
                .contains("UNIQUE KEY uq_group_marketing_group_active (tenant_id, group_jid, active_key)")
                .contains("idx_group_marketing_pool (tenant_id, reservation_token, released_at, id)")
                .contains("idx_group_marketing_waiting_expiry (tenant_id, occupancy_type, released_at, expires_at)")
                .contains("idx_group_marketing_task_lock (tenant_id, task_id, occupancy_type, released_at, id)");
    }
}
