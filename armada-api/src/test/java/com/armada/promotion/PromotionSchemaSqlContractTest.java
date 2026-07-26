package com.armada.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** V061-V069 推广模板、渠道与公共配对会话迁移的数据库无关 SQL 合同测试。 */
class PromotionSchemaSqlContractTest {

    private static final String MIGRATION =
            "db/migration/V061__promotion_template_channel_statistics.sql";

    private static final String COUNTRY_VALUE_MIGRATION =
            "db/migration/V062__promotion_channel_country_values.sql";

    private static final String TEMPLATE_SEED_MIGRATION =
            "db/migration/V063__promotion_template_visibility_and_seed.sql";

    private static final String TEMPLATE_DOMAIN_UNIQUE_MIGRATION =
            "db/migration/V064__promotion_template_single_domain.sql";

    private static final String DOMAIN_SOFT_DELETE_UNIQUE_MIGRATION =
            "db/migration/V065__promotion_domain_soft_delete_uniqueness.sql";

    private static final String CHANNEL_RUNTIME_CONFIG_MIGRATION =
            "db/migration/V066__promotion_channel_runtime_config.sql";

    private static final String PAIRING_ACCOUNT_INDEX_MIGRATION =
            "db/migration/V067__promotion_pairing_account_phone_index.sql";

    private static final String PAIRING_IP_RESERVATION_MIGRATION =
            "db/migration/V068__promotion_pairing_ip_reservation.sql";

    private static final String PAIRING_SESSION_MIGRATION =
            "db/migration/V069__promotion_pairing_session.sql";

    private static final Map<String, List<String>> TABLE_FIELDS = approvedTableFields();

    private static final List<String> DEFERRED_STATISTICS_TABLES = List.of(
            "promotion_channel_event",
            "promotion_channel_daily_metric",
            "promotion_channel_daily_ad_revision");

    @Test
    void migrationCreatesApprovedTableSetAndAccountCompatibility() throws IOException {
        String sql = migrationSql();

        for (String table : TABLE_FIELDS.keySet()) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("ADD COLUMN promotion_channel_id BIGINT");
        assertThat(sql).contains("MODIFY COLUMN channel_name VARCHAR(128)");
        assertThat(sql).contains("idx_account_promotion_channel");
        assertThat(sql).contains("promotion_channel_id BIGINT DEFAULT NULL COMMENT '")
                .contains("稳定推广渠道ID(→promotion_channel.id),历史账号可为空,例如 5001");
        assertThat(sql).contains("COMMENT '推广渠道名称快照,历史筛选兼容字段,例如 KK-代投印度-抽奖'");
    }

    @Test
    void migrationDefersChannelStatisticsTables() throws IOException {
        String sql = migrationSql();

        for (String table : DEFERRED_STATISTICS_TABLES) {
            assertThat(sql).doesNotContain("CREATE TABLE " + table + " (");
        }
    }

    @Test
    void everyDeclaredBusinessColumnCommentContainsExample() throws IOException {
        String sql = migrationSql();

        TABLE_FIELDS.forEach((table, fields) -> {
            String createBlock = createTableBlock(sql, table);
            fields.forEach(field -> {
                String declaration = createBlock.lines()
                        .map(String::trim)
                        .filter(line -> line.startsWith(field + " "))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(table + "." + field + " declaration missing"));
                assertThat(declaration)
                        .as(table + "." + field + " must have a business comment")
                        .contains("COMMENT '");
                assertThat(declaration)
                        .as(table + "." + field + " comment must include an example")
                        .contains("例如");
            });
        });
    }

    @Test
    void migrationDefinesApprovedUniqueAndQueryIndexes() throws IOException {
        String sql = migrationSql();

        assertThat(sql).contains("UNIQUE KEY uq_promotion_landing_template_code "
                + "(tenant_id, template_code)");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_domain_host (domain_host)");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_channel_code "
                + "(tenant_id, channel_code)");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_channel_tracking "
                + "(tenant_id, channel_id)");
    }

    @Test
    void countryValueMigrationBackfillsStableOptionValuesBeforeDroppingIds() throws IOException {
        String sql = migrationSql(COUNTRY_VALUE_MIGRATION);

        assertThat(sql).contains("ADD COLUMN target_country_value VARCHAR(16)");
        assertThat(sql).contains("ADD COLUMN preselected_country_value VARCHAR(16)");
        assertThat(sql).contains("WHEN pc.target_country_id IS NULL THEN 'MIXED'");
        assertThat(sql).contains("UPPER(tc.iso2)");
        assertThat(sql).contains("UPPER(pc_country.iso2)");
        assertThat(sql).contains("DROP COLUMN target_country_id");
        assertThat(sql).contains("DROP COLUMN preselected_country_id");
    }

    @Test
    void templateSeedMigrationKeepsTenantIsolationAndAddsFiveVisibleTemplates() throws IOException {
        String sql = migrationSql(TEMPLATE_SEED_MIGRATION);

        assertThat(sql).contains("CREATE TEMPORARY TABLE v063_template_seed_guard");
        assertThat(sql).contains("id IN (130, 40, 39, 38, 37)");
        assertThat(sql).contains("tenant_id = 1 AND template_code IN");
        assertThat(sql).contains("ADD COLUMN is_subaccount_visible TINYINT(1) NOT NULL DEFAULT 1");
        assertThat(sql).doesNotContain("DROP COLUMN tenant_id");
        assertThat(sql).contains("SELECT 130, 1, 'base_sex2', '约会二代'");
        assertThat(sql).contains("SELECT 40, 1, 'basic_earn', '基础领奖'");
        assertThat(sql).contains("SELECT 39, 1, 'basic_party_man', '基础约会-投男粉'");
        assertThat(sql).contains("SELECT 38, 1, 'basic_party_female', '基础约会-投女粉'");
        assertThat(sql).contains("SELECT 37, 1, 'base_sex', '约会二代'");
        assertThat(sql).contains("'[\"themeColor\", \"showAppDownload\"]'");
    }

    @Test
    void templateDomainMigrationEnforcesOneActiveMappingPerTenantTemplate() throws IOException {
        String sql = migrationSql(TEMPLATE_DOMAIN_UNIQUE_MIGRATION);

        assertThat(sql).contains("ALTER TABLE promotion_domain");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_domain_tenant_template "
                  + "(tenant_id, landing_template_id)");
    }

    @Test
    void domainSoftDeleteMigrationReleasesUniqueKeysWithoutLosingHistory() throws IOException {
        String sql = migrationSql(DOMAIN_SOFT_DELETE_UNIQUE_MIGRATION);

        assertThat(sql).contains("UPDATE promotion_domain d");
        assertThat(sql).contains("LEFT JOIN promotion_channel c");
        assertThat(sql).contains("c.promotion_domain_id = d.id");
        assertThat(sql).contains("c.deleted_at IS NULL");
        assertThat(sql).contains("AND c.id IS NULL");
        assertThat(sql).contains("ADD COLUMN is_active TINYINT(1)");
        assertThat(sql).contains("CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END");
        assertThat(sql).contains("DROP INDEX uq_promotion_domain_host");
        assertThat(sql).contains("DROP INDEX uq_promotion_domain_tenant_template");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_domain_active_host (domain_host, is_active)");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_domain_active_template "
                + "(tenant_id, landing_template_id, is_active)");
        assertThat(sql).contains("INDEX idx_promotion_channel_domain_active");
        assertThat(sql).contains("(tenant_id, promotion_domain_id, deleted_at, id)");
    }

    @Test
    void domainSoftDeleteMigrationGuardsEverySchemaChangeForFailedMigrationRetry() throws IOException {
        String sql = migrationSql(DOMAIN_SOFT_DELETE_UNIQUE_MIGRATION);

        assertThat(sql).contains("information_schema.columns");
        assertThat(sql).contains("column_name = 'is_active'");
        assertThat(sql).contains("information_schema.statistics");
        assertThat(sql).contains("index_name = 'uq_promotion_domain_host'");
        assertThat(sql).contains("index_name = 'uq_promotion_domain_tenant_template'");
        assertThat(sql).contains("index_name = 'uq_promotion_domain_active_host'");
        assertThat(sql).contains("index_name = 'uq_promotion_domain_active_template'");
        assertThat(sql).contains("index_name = 'idx_promotion_channel_domain_active'");
        assertThat(sql).contains("PREPARE", "EXECUTE", "DEALLOCATE PREPARE");
    }

    @Test
    void runtimeConfigMigrationAddsBothChannelOwnedFieldsWithCompatibleDefaults() throws IOException {
        String sql = migrationSql(CHANNEL_RUNTIME_CONFIG_MIGRATION);

        assertThat(sql).contains("ADD COLUMN theme_color VARCHAR(7)");
        assertThat(sql).contains("DEFAULT ''#e11d48''");
        assertThat(sql).contains("ADD COLUMN is_app_download_shown TINYINT(1)");
        assertThat(sql).contains("DEFAULT 1");
        assertThat(sql).contains("例如 #e11d48", "例如 1");
        assertThat(sql).doesNotContain("CREATE TABLE");
    }

    @Test
    void pairingSessionMigrationKeepsSecretsOutAndDefinesLifecycleGuards() throws IOException {
        String sql = migrationSql(PAIRING_SESSION_MIGRATION);

        assertThat(sql).contains("CREATE TABLE promotion_pairing_session (");
        assertThat(sql).contains("session_token_hash CHAR(64)");
        assertThat(sql).doesNotContain("session_token VARCHAR", "credential_json", "access_token");
        assertThat(sql).contains("status IN (1, 2, 3)");
        assertThat(sql).contains("UNIQUE KEY uq_promotion_pairing_active_account");
        assertThat(sql).doesNotContain("uq_promotion_pairing_owned_account");
        assertThat(sql).contains("KEY idx_promotion_pairing_expiry_scan (status, expires_at, id)");

        assertThat(migrationSql(PAIRING_ACCOUNT_INDEX_MIGRATION))
                .contains("CREATE INDEX idx_account_ws_phone_active ON account (ws_phone, is_active)")
                .doesNotContain("ALTER TABLE", "CREATE TABLE");
        assertThat(migrationSql(PAIRING_IP_RESERVATION_MIGRATION))
                .contains("ADD COLUMN pairing_session_id BIGINT")
                .contains("ADD UNIQUE KEY uq_ip_proxy_pairing_session")
                .contains("4=配对占用")
                .doesNotContain("CREATE INDEX", "CREATE TABLE");
    }

    private String migrationSql() throws IOException {
        return migrationSql(MIGRATION);
    }

    private String migrationSql(String resource) throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(resource), resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String createTableBlock(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE " + table + " (");
        int end = sql.indexOf(") ENGINE=InnoDB", start);
        assertThat(start).as(table + " start").isGreaterThanOrEqualTo(0);
        assertThat(end).as(table + " end").isGreaterThan(start);
        return sql.substring(start, end);
    }

    private static Map<String, List<String>> approvedTableFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("promotion_landing_template", List.of(
                "id", "tenant_id", "template_code", "template_name", "preview_uri",
                "supported_params", "status", "remark", "created_by", "updated_by",
                "created_at", "updated_at", "deleted_at"));
        fields.put("promotion_domain", List.of(
                "id", "tenant_id", "domain_host", "landing_template_id", "created_by", "updated_by",
                "created_at", "updated_at", "deleted_at"));
        fields.put("promotion_channel", List.of(
                "id", "tenant_id", "channel_code", "channel_name", "owner_user_id",
                "promotion_domain_id", "target_country_id", "preselected_country_id", "platform",
                "is_in_app_open_allowed", "is_marketing_allowed", "status",
                "created_by", "updated_by", "created_at", "updated_at", "deleted_at"));
        fields.put("promotion_channel_tracking_config", List.of(
                "id", "tenant_id", "channel_id", "provider_type", "tracking_id",
                "access_token_ciphertext", "encryption_key_id", "token_fingerprint",
                "token_expires_at", "lead_event_name", "login_request_event_name",
                "login_success_event_name", "last_probe_status", "last_probe_event_name",
                "last_probe_event_id", "last_probe_error_code", "last_probe_error_message",
                "last_probed_at", "created_by", "updated_by", "created_at", "updated_at",
                "deleted_at"));
        return fields;
    }
}
