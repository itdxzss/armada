package com.armada.task.mapper;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/** V211 真跑建表/索引与列默认；MySQL 动态 DDL 守卫另做结构验证。 */
class PullTaskCreatorDeletionMigrationTest {
    @Test void identityIndexUsesTheMigrationExpressionAndTracksPhoneChanges() throws Exception {
        String sql = new ClassPathResource("db/migration/V212__account_creator_deletion_identity_index.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("information_schema.columns", "information_schema.statistics",
                "DEALLOCATE PREPARE creator_identity_column_stmt", "DEALLOCATE PREPARE creator_identity_index_stmt");
        var jdbc = new JdbcTemplate(PullTaskNormalLinkH2Support.dataSource("creator_delete_identity_migration"));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE account(id BIGINT PRIMARY KEY, ws_phone VARCHAR(32))");
        var alters = Pattern.compile("(?m)^\\s*'(ALTER TABLE (?:[^']|'')*)',\\s*$").matcher(sql);
        int count = 0;
        while (alters.find()) {
            // H2 supports the same expression, but has no MySQL STORED keyword.
            jdbc.execute(alters.group(1).replace("''", "'").replace(" STORED", ""));
            count++;
        }
        assertThat(count).isEqualTo(2);
        jdbc.update("INSERT INTO account(id,ws_phone) VALUES(1,' +1 2025550101 '),(2,'12025550101')");
        assertThat(jdbc.queryForList("SELECT creator_deletion_identity_phone FROM account ORDER BY id", String.class))
                .containsExactly("12025550101", "12025550101");
        jdbc.update("UPDATE account SET ws_phone='12025550103' WHERE id=1");
        assertThat(jdbc.queryForObject("SELECT creator_deletion_identity_phone FROM account WHERE id=1", String.class))
                .isEqualTo("12025550103");
    }

    @Test void actualMigrationCreatesDefaultOffAndGlobalOneShotIdentityConstraints() throws Exception {
        String sql = new ClassPathResource("db/migration/V211__new_group_creator_deletion.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("information_schema.columns", "column_name = 'is_creator_delete_after_takeover'",
                "DEFAULT 0", "PREPARE creator_delete_statement", "DEALLOCATE PREPARE creator_delete_statement");
        var db = PullTaskNormalLinkH2Support.dataSource("creator_delete_migration");
        var jdbc = new JdbcTemplate(db);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE pull_task(id BIGINT PRIMARY KEY)");
        var alter = Pattern.compile("'ALTER TABLE (.*?)',", Pattern.DOTALL).matcher(sql);
        assertThat(alter.find()).isTrue();
        jdbc.execute("ALTER TABLE " + alter.group(1).replace("''", "'"));
        String tables = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS"));
        try (var connection = db.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8)));
        }
        jdbc.update("INSERT INTO pull_task(id) VALUES(1)");
        assertThat(jdbc.queryForObject("SELECT is_creator_delete_after_takeover FROM pull_task WHERE id=1", Integer.class)).isZero();
        String insert = """
                INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,identity_hash,
                  creator_phone,protocol_account_id,create_operation_id,lifecycle,created_at,updated_at)
                VALUES(?,?,?,?,?,'12025550101','test-route','ptgc-test','RESERVED',1,1)
                """;
        jdbc.update(insert, 1, 7, 10, 20, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, 2, 8, 11, 21, "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_creator_deletion", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_task_creator_deletion", Integer.class)).isZero();
    }
}
