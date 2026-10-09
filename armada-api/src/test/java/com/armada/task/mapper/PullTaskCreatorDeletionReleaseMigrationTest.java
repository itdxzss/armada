package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 真跑释放历史迁移，确保幂等且同账号、同身份可保留多次历史。 */
class PullTaskCreatorDeletionReleaseMigrationTest {
    @Test
    void migrationIsIdempotentAndAllowsRepeatedReleaseHistory() throws Exception {
        var source = PullTaskNormalLinkH2Support.dataSource("creator_release_migration");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE pull_task_creator_deletion(status TINYINT DEFAULT 0 NOT NULL)");
        var script = new ClassPathResource("db/migration/V217__account_creator_deletion_release.sql");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);
        }
        String insert = """
                INSERT INTO account_creator_deletion_release(account_id,tenant_id,task_id,group_execution_id,
                  identity_hash,creator_phone,protocol_account_id,create_operation_id,lifecycle,created_at,
                  updated_at,released_at,release_reason,execution_status_at_release)
                VALUES(1,7,9,11,?,'12025550101','route','create','RESERVED',1,1,2,'TASK_ENDED',6)
                """;
        jdbc.update(insert, "a".repeat(64));
        jdbc.update(insert, "a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion_release", Integer.class))
                .isEqualTo(2);
        assertThat(script.getContentAsString(StandardCharsets.UTF_8))
                .contains("IF NOT EXISTS", "(account_id)", "(tenant_id, group_execution_id)", "6预留已释放");
    }
}
