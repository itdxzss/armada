package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 历史群一次性拉人执行 owner 与幂等键范围的 Flyway 结构契约。 */
class HistoricalGroupPullUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V150__historical_group_pull_user_data_ownership.sql";

    @Test
    void v150AddsOwnerWithoutGuessingHistoryAndMovesIdempotencyIntoOwnerScope()
            throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'historical_group_pull_execution'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("unowned_idempotency_key varchar(128)")
                    .contains("if(owner_user_id is null, idempotency_key, null)")
                    .contains("idx_hgpe_owner_time (tenant_id, owner_user_id, created_at, id)")
                    .contains("uq_hgpe_owner_idempotency (tenant_id, owner_user_id, idempotency_key)")
                    .contains("uq_hgpe_unowned_idempotency (tenant_id, unowned_idempotency_key)")
                    .contains("drop index uq_hgpe_tenant_idempotency")
                    .contains("create temporary table tmp_v149_hgpe_index_guard")
                    .contains("group_concat(column_name order by seq_in_index separator ',')")
                    .contains("select max(non_unique)")
                    .doesNotContain("update historical_group_pull_execution set owner_user_id");

            assertThat(sql.indexOf("uq_hgpe_owner_idempotency"))
                    .isLessThan(sql.indexOf("drop index uq_hgpe_tenant_idempotency"));
        }
    }
}
