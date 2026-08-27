package com.armada.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 推广 CAPI outbox 用户归属快照的 Flyway 结构契约。 */
class PromotionCapiOutboxUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V151__promotion_capi_outbox_user_data_ownership.sql";

    @Test
    void v151AddsNullableOwnerSnapshotWithoutGuessingHistoricalOwnership() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'promotion_capi_event_outbox'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("idx_promotion_capi_owner (tenant_id, owner_user_id, created_at, id)")
                    .contains("information_schema.columns")
                    .contains("information_schema.statistics")
                    .contains("prepare promotion_capi_owner_column_stmt")
                    .contains("deallocate prepare promotion_capi_owner_index_stmt")
                    .doesNotContain("update promotion_capi_event_outbox set owner_user_id");
        }
    }
}
