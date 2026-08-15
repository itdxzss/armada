package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 8.4 上验证 V117 可执行、可重入及关键物理约束。 */
@Testcontainers
class GroupDataModelFoundationMigrationMysqlTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "wa_group",
            "wa_group_profile",
            "wa_group_invite",
            "wa_group_participant",
            "wa_account_group_binding",
            "account_group_sync_state");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @BeforeEach
    void resetTables() throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS wa_account_group_binding");
            statement.execute("DROP TABLE IF EXISTS account_group_sync_state");
            statement.execute("DROP TABLE IF EXISTS wa_group_participant");
            statement.execute("DROP TABLE IF EXISTS wa_group_invite");
            statement.execute("DROP TABLE IF EXISTS wa_group_profile");
            statement.execute("DROP TABLE IF EXISTS wa_group");
        }
    }

    @Test
    void migrationCreatesExactlySixTablesAndCanRunTwice() throws Exception {
        executeMigration();
        executeMigration();

        assertThat(actualTables()).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT collation_name
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'wa_group'
                       AND column_name = 'group_jid'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("ascii_bin");
        }
    }

    @Test
    void checksRejectInvalidIdentityAndBaselineClassification() throws Exception {
        executeMigration();

        assertThatThrownBy(() -> execute("""
                INSERT INTO wa_group_participant
                  (tenant_id, group_id, created_at, updated_at)
                VALUES (7, 10, 100, 100)
                """))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> execute("""
                INSERT INTO wa_account_group_binding
                  (tenant_id, account_id, group_id, participant_id,
                   was_in_initial_baseline, first_post_control_observed_at,
                   created_at, updated_at)
                VALUES (7, 20, 10, 30, 1, 100, 100, 100)
                """))
                .isInstanceOf(SQLException.class);
    }

    private void executeMigration() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V117__group_data_model_foundation.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }

    private Set<String> actualTables() throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN (
                         'wa_group', 'wa_group_profile', 'wa_group_invite',
                         'wa_group_participant', 'wa_account_group_binding',
                         'account_group_sync_state')
                     """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
