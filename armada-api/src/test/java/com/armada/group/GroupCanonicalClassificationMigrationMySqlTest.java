package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 8 上执行 V140，覆盖回填决策、句柄绑定和 CHECK 约束。 */
@Testcontainers
class GroupCanonicalClassificationMigrationMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_classification_migration")
            .withUsername("armada")
            .withPassword("armada");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFixture() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createPreV140Schema();
        seedEvidence();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V140__group_canonical_first_classification.sql"));
        }
    }

    @Test
    void earliestReliableFactWinsAndTieOrAmbiguityFallsBackToHistorical() {
        assertThat(jdbc.queryForList("""
                SELECT id, group_classification, group_classified_at,
                       group_classification_source
                FROM wa_group
                ORDER BY id
                """))
                .extracting(
                        row -> row.get("group_classification"),
                        row -> row.get("group_classified_at"),
                        row -> row.get("group_classification_source"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, 900L, 3),
                        org.assertj.core.groups.Tuple.tuple(1, 1_000L, 3),
                        org.assertj.core.groups.Tuple.tuple(
                                1,
                                jdbc.queryForObject("""
                                        SELECT classified_at
                                        FROM wa_group_classification_migration_audit
                                        WHERE tenant_id = 7 AND group_id = 3
                                        """, Long.class),
                                4),
                        org.assertj.core.groups.Tuple.tuple(0, null, null));

        assertThat(jdbc.queryForList("""
                SELECT group_id, resolution_rule, resolved_classification
                FROM wa_group_classification_migration_audit
                ORDER BY group_id
                """))
                .extracting(
                        row -> row.get("resolution_rule"),
                        row -> row.get("resolved_classification"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 2),
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(4, 1),
                        org.assertj.core.groups.Tuple.tuple(0, 0));
    }

    @Test
    void deterministicAccountSyncHandleIsBoundBeforeCanonicalListCutover() {
        assertThat(jdbc.queryForObject(
                "SELECT group_id FROM group_link WHERE id = 11", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void schemaChecksRejectInvalidCanonicalHeader() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wa_group
                SET group_classification = 9
                WHERE id = 4
                """))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wa_group
                SET group_classification = 1,
                    group_classified_at = NULL,
                    group_classification_source = NULL
                WHERE id = 4
                """))
                .isInstanceOf(DataAccessException.class);
    }

    private static void createPreV140Schema() {
        List.of(
                """
                CREATE TABLE wa_group (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  origin TINYINT NOT NULL DEFAULT 5,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  UNIQUE KEY uq_wa_group_identity (tenant_id, group_jid)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE group_link (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  group_id BIGINT DEFAULT NULL,
                  link_url VARCHAR(512) NOT NULL,
                  is_historical TINYINT NOT NULL DEFAULT 0,
                  is_post_control TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE wa_account_group_binding (
                  id BIGINT NOT NULL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  group_id BIGINT NOT NULL,
                  was_in_initial_baseline TINYINT,
                  first_post_control_observed_at BIGINT
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE account_group_sync_state (
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  baseline_state TINYINT,
                  baseline_completeness TINYINT,
                  baseline_captured_at BIGINT,
                  PRIMARY KEY (tenant_id, account_id)
                ) ENGINE=InnoDB
                """).forEach(jdbc::execute);
    }

    private static void seedEvidence() {
        jdbc.update("""
                INSERT INTO wa_group
                  (id, tenant_id, group_jid, origin, created_at, updated_at)
                VALUES
                  (1, 7, '120363-migration-post@g.us', 5, 100, 100),
                  (2, 7, '120363-migration-tie@g.us', 5, 100, 100),
                  (3, 7, '120363-migration-ambiguous@g.us', 5, 100, 100),
                  (4, 7, '120363-migration-empty@g.us', 5, 100, 100)
                """);
        jdbc.update("""
                INSERT INTO group_link
                  (id, tenant_id, group_id, link_url, is_historical, is_post_control)
                VALUES
                  (11, 7, NULL, 'wa://group/120363-migration-post@g.us', 1, 1),
                  (12, 7, 2, 'wa://group/120363-migration-tie@g.us', 1, 1),
                  (13, 7, 3, 'wa://group/120363-migration-ambiguous@g.us', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO account_group_sync_state
                  (tenant_id, account_id, baseline_state, baseline_completeness,
                   baseline_captured_at)
                VALUES
                  (7, 101, 2, 1, 1000),
                  (7, 102, 2, 1, 1000)
                """);
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, was_in_initial_baseline,
                   first_post_control_observed_at)
                VALUES
                  (21, 7, 101, 1, 1, NULL),
                  (22, 7, 102, 1, 0, 900),
                  (23, 7, 101, 2, 1, NULL),
                  (24, 7, 102, 2, 0, 1000)
                """);
    }
}
