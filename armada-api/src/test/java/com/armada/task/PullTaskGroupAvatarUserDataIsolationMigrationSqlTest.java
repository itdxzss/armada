package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 拉群头像文件用户归属 Flyway 结构契约。 */
class PullTaskGroupAvatarUserDataIsolationMigrationSqlTest {

    private static final String MIGRATION =
            "/db/migration/V146__pull_task_group_avatar_user_data_ownership.sql";

    @Test
    void v146CreatesIndependentAvatarOwnershipRoot() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");

            assertThat(sql)
                    .contains("create table if not exists pull_task_group_avatar_file")
                    .contains("owner_user_id bigint not null")
                    .contains("unique key uq_pull_task_group_avatar_file_key (tenant_id, file_key)")
                    .contains("idx_pull_task_group_avatar_file_owner (tenant_id, owner_user_id, id)")
                    .doesNotContain("insert into pull_task_group_avatar_file select")
                    .doesNotContain("update pull_task_group_avatar_file");
        }
    }
}
