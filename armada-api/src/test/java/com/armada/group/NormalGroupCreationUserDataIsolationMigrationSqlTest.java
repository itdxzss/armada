package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 新建普群任务 owner 与幂等键范围的 Flyway 结构契约。 */
class NormalGroupCreationUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V148__normal_group_creation_user_data_ownership.sql";

    @Test
    void v148AddsOwnerWithoutGuessingHistoryAndMovesIdempotencyIntoOwnerScope()
            throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("table_name = 'normal_group_creation_task'")
                    .contains("add column owner_user_id bigint default null")
                    .contains("unowned_idempotency_key varchar(64)")
                    .contains("if(owner_user_id is null, idempotency_key, null)")
                    .contains("idx_normal_group_creation_task_owner (tenant_id, owner_user_id, deleted_at, status, created_at, id)")
                    .contains("uq_normal_group_creation_task_owner_idem (tenant_id, owner_user_id, idempotency_key)")
                    .contains("uq_normal_group_creation_task_unowned_idem (tenant_id, unowned_idempotency_key)")
                    .contains("drop index uq_normal_group_creation_task_idem")
                    .contains("create temporary table tmp_v148_normal_group_creation_index_guard")
                    .contains("group_concat(column_name order by seq_in_index separator ',')")
                    .contains("select max(non_unique)")
                    .doesNotContain("update normal_group_creation_task set owner_user_id");

            assertThat(sql.indexOf("uq_normal_group_creation_task_owner_idem"))
                    .isLessThan(sql.indexOf("drop index uq_normal_group_creation_task_idem"));
        }
    }
}
