package com.armada.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 普通营销与拉群营销公共任务根用户归属的 Flyway 结构契约。 */
class MarketingTaskUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V143__marketing_task_user_data_ownership.sql";

    @Test
    void v143AddsNullableOwnerWithoutGuessingHistoricalOwnership() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'marketing_task'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_marketing_task_owner (tenant_id, owner_user_id, business_type, deleted_at, id)")
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("prepare marketing_task_owner_column_stmt")
                    .contains("deallocate prepare marketing_task_owner_index_stmt")
                    .doesNotContain("update marketing_task set owner_user_id");
        }
    }
}
