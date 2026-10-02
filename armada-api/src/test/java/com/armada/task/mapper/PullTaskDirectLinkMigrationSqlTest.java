package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** MySQL 元数据守卫与列注释在 H2 不支持的迁移部分用结构断言覆盖。 */
class PullTaskDirectLinkMigrationSqlTest {
    @Test
    void migrationPreservesOldModeDefaultAndMakesManagerOptional() throws Exception {
        try (var stream = new ClassPathResource("db/migration/V208__pull_task_direct_link.sql").getInputStream()) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("DEFAULT 'PASTED_LINK'", "DIRECT_LINK", "10普通拉手链接入群",
                    "MODIFY COLUMN manager_group_id BIGINT NULL", "MODIFY COLUMN manager_group_name VARCHAR(100) NULL",
                    "information_schema.columns", "information_schema.statistics",
                    "UNIQUE KEY uq_pull_task_creation_request (tenant_id, created_by, creation_request_id)");
            assertThat(sql).doesNotContain("DROP TABLE", "UPDATE pull_task SET");
        }
    }
}
