package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V215 的 MySQL 守卫结构合同，以及真实 DDL、离线起点回填和租户约束的 H2 验证。 */
class AccountTakeoverBreakerMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V215__account_takeover_breaker.sql");
    private static final Path ROLLBACK = Path.of(
            "../.harness/changes/2026-10-08-takeover-business-continuity/rollback.sql");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:account_takeover_breaker_migration;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE account_state (
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    login_state TINYINT,
                    last_state_sync_time BIGINT,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, account_id)
                )
                """);
    }

    @Test
    void guardsColumnAdditionAndCreatesTenantScopedBreakerStructure() throws Exception {
        String sql = migration();

        assertThat(sql).contains("information_schema.columns", "table_schema = DATABASE()",
                "table_name = 'account_state'", "column_name = 'offline_since'",
                "PREPARE stmt FROM @sql", "EXECUTE stmt", "DEALLOCATE PREPARE stmt",
                "CREATE TABLE IF NOT EXISTS account_takeover_breaker",
                "UNIQUE KEY uq_tenant_account (tenant_id, account_id)",
                "KEY idx_tenant_tripped (tenant_id, tripped_at)");
        assertThat(sql).containsPattern("(?s)IF\\(\\s*@offline_since_col_exists = 0,.*?'SELECT 1'");

        jdbc.execute(offlineColumnDdl(sql));
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name='account_state' AND column_name='offline_since'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("""
                SELECT remarks FROM information_schema.columns
                WHERE table_name='account_state' AND column_name='offline_since'
                """, String.class)).contains("连续离线", "毫秒");
    }

    @Test
    void backfillsOfflineAndPendingAccountsWithoutChangingOnlineOrEstablishedOrigins() throws Exception {
        String sql = migration();
        jdbc.execute(offlineColumnDdl(sql));
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, login_state, last_state_sync_time, updated_at, offline_since) VALUES
                (7,1,1,100,200,NULL), (7,2,2,110,210,NULL),
                (7,3,3,NULL,220,NULL), (7,4,NULL,130,230,NULL),
                (7,5,2,140,240,80), (8,2,2,NULL,250,NULL)
                """);

        jdbc.execute(backfillSql(sql));

        assertThat(jdbc.queryForList("""
                SELECT offline_since FROM account_state ORDER BY tenant_id,account_id
                """, Long.class)).containsExactly(null, 110L, 220L, 130L, 80L, 250L);
        var afterFirstRun = jdbc.queryForList("SELECT * FROM account_state ORDER BY tenant_id,account_id");
        jdbc.execute(backfillSql(sql));
        assertThat(jdbc.queryForList("SELECT * FROM account_state ORDER BY tenant_id,account_id"))
                .isEqualTo(afterFirstRun);
    }

    @Test
    void enforcesOneBreakerPerTenantAccountAndInitializesAnUntrippedWindow() throws Exception {
        String ddl = breakerDdl(migration());
        jdbc.execute(ddl);
        jdbc.execute(ddl);
        jdbc.update("""
                INSERT INTO account_takeover_breaker (tenant_id, account_id, created_at, updated_at)
                VALUES (7,100,10,10), (8,100,20,20)
                """);

        assertThat(jdbc.queryForList("SELECT kick_count FROM account_takeover_breaker", Integer.class))
                .containsExactly(0, 0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_takeover_breaker
                WHERE window_started_at IS NULL AND tripped_at IS NULL
                """, Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO account_takeover_breaker (tenant_id, account_id, created_at, updated_at)
                VALUES (7,100,30,30)
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO account_takeover_breaker (tenant_id, account_id, created_at, updated_at)
                VALUES (NULL,101,30,30)
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.index_columns
                WHERE table_name='account_takeover_breaker' AND index_name='idx_tenant_tripped'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("tenant_id", "tripped_at");
    }

    @Test
    void rollbackRemovesOnlyTheNewStructuresAndKeepsExistingAccountFacts() throws Exception {
        String sql = migration();
        jdbc.execute(offlineColumnDdl(sql));
        jdbc.execute(breakerDdl(sql));
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, login_state, last_state_sync_time, updated_at, offline_since)
                VALUES (7,100,2,100,200,100)
                """);

        String rollback = Files.readString(ROLLBACK, StandardCharsets.UTF_8);
        jdbc.execute(extract(rollback, "DROP TABLE IF EXISTS account_takeover_breaker\\s*;"));
        jdbc.execute(extract(rollback, "ALTER TABLE account_state\\s+DROP COLUMN offline_since\\s*;"));

        assertThat(jdbc.queryForObject("SELECT login_state FROM account_state", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_state_sync_time FROM account_state", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='account_state' AND column_name='offline_since'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_name='account_takeover_breaker'
                """, Integer.class)).isZero();
    }

    private static String migration() throws Exception {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String offlineColumnDdl(String sql) {
        String quoted = extract(sql, "'ALTER TABLE account_state.*?',\\s*'SELECT 1'");
        return quoted.substring(1, quoted.lastIndexOf("',")).replace("''", "'");
    }

    private static String breakerDdl(String sql) {
        return extract(sql, "CREATE TABLE IF NOT EXISTS account_takeover_breaker.*?\\)\\s*ENGINE.*?;")
                .replaceFirst("(?is)\\s*ENGINE\\s*=.*", "");
    }

    private static String backfillSql(String sql) {
        return extract(sql, "UPDATE account_state\\s+SET offline_since\\s*=.*?;");
    }

    private static String extract(String sql, String pattern) {
        var matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        assertThat(matcher.find()).as("迁移中存在可执行 SQL: %s", pattern).isTrue();
        return matcher.group();
    }
}
