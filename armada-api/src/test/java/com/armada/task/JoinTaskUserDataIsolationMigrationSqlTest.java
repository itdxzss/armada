package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 进群任务用户归属的 Flyway 结构契约。 */
class JoinTaskUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V144__join_task_user_data_ownership.sql";

    @Test
    void v144AddsNullableOwnerWithoutGuessingHistoricalOwnership() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'join_task'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_join_task_owner (tenant_id, owner_user_id, deleted_at, id)")
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("prepare join_task_owner_column_stmt")
                    .contains("deallocate prepare join_task_owner_index_stmt")
                    .doesNotContain("update join_task set owner_user_id");
        }
    }
}
