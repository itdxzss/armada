package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** V214 真跑状态注释与注销回填，保留未确认账号和原注销证据。 */
class AccountDeregisteredStateMigrationTest {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:account_deregistered_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE account_state (
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    account_state TINYINT DEFAULT NULL,
                    login_state TINYINT,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, account_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE account_creator_deletion (
                    account_id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    lifecycle VARCHAR(16) NOT NULL,
                    completed_at BIGINT
                )
                """);
    }

    @Test
    void backfillsOnlyConfirmedDeletionsAndPreservesEvidenceTimes() throws Exception {
        jdbc.update("INSERT INTO account_state VALUES (7,1,2,2,100),(7,2,NULL,2,200)");
        jdbc.update("INSERT INTO account_creator_deletion VALUES (1,7,'DELETED',80),(2,7,'DELETED',180)");

        migrate();

        assertThat(jdbc.queryForList("SELECT account_state FROM account_state ORDER BY account_id", Integer.class))
                .containsExactly(9, 9);
        assertThat(jdbc.queryForList("SELECT updated_at FROM account_state ORDER BY account_id", Long.class))
                .containsExactly(100L, 200L);
        assertThat(jdbc.queryForList("SELECT completed_at FROM account_creator_deletion ORDER BY account_id", Long.class))
                .containsExactly(80L, 180L);
        assertThat(jdbc.queryForList("SELECT login_state FROM account_state ORDER BY account_id", Integer.class))
                .containsExactly(2, 2);
        assertThat(jdbc.queryForObject("""
                SELECT remarks FROM information_schema.columns
                WHERE table_name='account_state' AND column_name='account_state'
                """, String.class)).contains("9注销", "NULL=未上报");
    }

    @Test
    void leavesReservedDeletingUnconfirmedAndOrdinaryOfflineAccountsUnchanged() throws Exception {
        jdbc.update("INSERT INTO account_state VALUES (7,1,2,2,100),(7,2,2,2,100),(7,3,2,2,100),(7,4,2,2,100)");
        jdbc.update("""
                INSERT INTO account_creator_deletion VALUES
                (1,7,'RESERVED',NULL),(2,7,'DELETING',80),(3,7,'DELETED',NULL)
                """);

        migrate();

        assertThat(jdbc.queryForList("SELECT account_state FROM account_state ORDER BY account_id", Integer.class))
                .containsExactly(2, 2, 2, 2);
    }

    @Test
    void matchesBothTenantAndAccountIdentity() throws Exception {
        jdbc.update("INSERT INTO account_state VALUES (7,1,2,2,100),(8,1,2,2,100),(7,2,2,2,100)");
        jdbc.update("INSERT INTO account_creator_deletion VALUES (1,7,'DELETED',80)");

        migrate();

        assertThat(jdbc.queryForList("SELECT account_state FROM account_state ORDER BY tenant_id,account_id", Integer.class))
                .containsExactly(9, 2, 2);
    }

    @Test
    void canRunTwiceWithoutChangingConfirmedAuditOrAlreadyDeregisteredAccounts() throws Exception {
        jdbc.update("INSERT INTO account_state VALUES (7,1,2,2,100),(7,2,9,2,200)");
        jdbc.update("INSERT INTO account_creator_deletion VALUES (1,7,'DELETED',80),(2,7,'DELETED',180)");

        migrate();
        var statesAfterFirstRun = jdbc.queryForList("SELECT * FROM account_state ORDER BY account_id");
        var evidenceAfterFirstRun = jdbc.queryForList("SELECT * FROM account_creator_deletion ORDER BY account_id");
        migrate();

        assertThat(jdbc.queryForList("SELECT * FROM account_state ORDER BY account_id")).isEqualTo(statesAfterFirstRun);
        assertThat(jdbc.queryForList("SELECT * FROM account_creator_deletion ORDER BY account_id")).isEqualTo(evidenceAfterFirstRun);
    }

    private void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V214__account_deregistered_state.sql"));
        }
    }
}
