package com.armada.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** V058 推广模板与渠道管理迁移的数据库无关 SQL 合同测试。 */
class PromotionSchemaSqlContractTest {

    private static final String MIGRATION =
            "db/migration/V058__promotion_template_channel_statistics.sql";

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

    private String migrationSql() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(MIGRATION), MIGRATION)) {
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
