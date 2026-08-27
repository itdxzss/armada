package com.armada.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 营销导出作业持久化可信数据范围的 Flyway 结构契约。 */
class MarketingExportJobDataScopeMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V152__marketing_export_job_data_scope.sql";

    @Test
    void v152AddsNullableScopeSnapshotWithoutGuessingHistoricalRoles() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'marketing_task_export_job'")
                    .contains("add column data_scope_mode varchar(8) default null")
                    .contains("information_schema.columns")
                    .contains("prepare marketing_export_scope_column_stmt")
                    .contains("deallocate prepare marketing_export_scope_column_stmt")
                    .doesNotContain("update marketing_task_export_job set data_scope_mode");
        }
    }
}
