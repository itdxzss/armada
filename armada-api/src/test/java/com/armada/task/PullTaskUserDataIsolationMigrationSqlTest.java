package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 拉群任务公共根用户归属的 Flyway 结构契约。 */
class PullTaskUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V146__pull_task_user_data_ownership.sql";

    @Test
    void v146AddsNullableOwnerWithoutGuessingHistoricalOwnership() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'pull_task'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_pull_task_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("prepare pull_task_owner_column_stmt")
                    .contains("deallocate prepare pull_task_owner_index_stmt")
                    .doesNotContain("update pull_task set owner_user_id");
        }
    }
}
