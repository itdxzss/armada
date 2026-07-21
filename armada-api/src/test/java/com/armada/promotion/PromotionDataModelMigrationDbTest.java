package com.armada.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.testsupport.DbTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V061 推广模板与渠道管理数据模型的真实 MySQL 验证。 */
class PromotionDataModelMigrationDbTest extends DbTestBase {

    private static final List<String> TABLES = List.of(
            "promotion_landing_template",
            "promotion_domain",
            "promotion_channel",
            "promotion_channel_tracking_config");

    private static final List<String> DEFERRED_STATISTICS_TABLES = List.of(
            "promotion_channel_event",
            "promotion_channel_daily_metric",
            "promotion_channel_daily_ad_revision");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v061CreatesAllApprovedTablesWithArmadaStorageDefaults() {
        for (String table : TABLES) {
            assertThat(tableExists(table)).as(table).isTrue();
            assertThat(tableEngine(table)).as(table + " engine").isEqualToIgnoringCase("InnoDB");
            assertThat(tableCollation(table)).as(table + " collation")
                    .isEqualToIgnoringCase("utf8mb4_0900_ai_ci");
        }
    }

    @Test
    void v061DefersChannelStatisticsTables() {
        for (String table : DEFERRED_STATISTICS_TABLES) {
            assertThat(tableExists(table)).as(table).isFalse();
        }
    }

    @Test
    void everyNewColumnCommentContainsMeaningAndExample() {
        for (String table : TABLES) {
            List<String> comments = jdbc.queryForList(
                    "SELECT column_comment FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                    String.class,
                    table);
            assertThat(comments).as(table + " comments").isNotEmpty();
            assertThat(comments).allSatisfy(comment -> assertThat(comment)
                    .as(table + " column comment")
                    .isNotBlank()
                    .contains("例如"));
        }
    }

    @Test
    void indexesUseApprovedLeftmostColumnOrder() {
        assertThat(indexColumns("promotion_landing_template", "uq_promotion_landing_template_code"))
                .containsExactly("tenant_id", "template_code");
        assertThat(indexColumns("promotion_domain", "uq_promotion_domain_host"))
                .containsExactly("domain_host");
        assertThat(indexColumns("promotion_channel", "idx_promotion_channel_list"))
                .containsExactly("tenant_id", "deleted_at", "created_at", "id");
        assertThat(indexColumns(
                "promotion_channel_tracking_config", "uq_promotion_channel_tracking"))
                .containsExactly("tenant_id", "channel_id");
    }

    @Test
    void accountKeepsSnapshotAndAddsStableChannelReference() {
        assertThat(columnType("account", "promotion_channel_id")).isEqualTo("bigint");
        assertThat(characterLength("account", "channel_name")).isEqualTo(128L);
        assertThat(columnComment("account", "promotion_channel_id")).contains("例如 5001");
        assertThat(columnComment("account", "channel_name")).contains("例如 KK-代投印度-抽奖");
        assertThat(indexColumns("account", "idx_account_promotion_channel"))
                .containsExactly("tenant_id", "promotion_channel_id", "deleted_at", "created_at");
    }

    @Test
    void tenantCodesAreScopedButActiveDomainOwnershipIsGlobal() {
        long now = System.currentTimeMillis();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String templateCode = "tpl_" + suffix;
        String domain = suffix + ".example.test";

        insertTemplate(1L, templateCode, now);
        assertThatThrownBy(() -> insertTemplate(1L, templateCode, now))
                .isInstanceOf(DataAccessException.class);
        insertTemplate(2L, templateCode, now);

        jdbc.update(
                "INSERT INTO promotion_domain "
                        + "(tenant_id, domain_host, landing_template_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                1L, domain, 1001L, now, now);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO promotion_domain "
                        + "(tenant_id, domain_host, landing_template_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                2L, domain, 2001L, now, now))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertTemplate(long tenantId, String templateCode, long now) {
        jdbc.update(
                "INSERT INTO promotion_landing_template "
                + "(tenant_id, template_code, template_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                tenantId, templateCode, "测试模板-" + templateCode, now, now);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private String tableEngine(String tableName) {
        return jdbc.queryForObject(
                "SELECT engine FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName);
    }

    private String tableCollation(String tableName) {
        return jdbc.queryForObject(
                "SELECT table_collation FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName);
    }

    private List<String> indexColumns(String tableName, String indexName) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                        + "ORDER BY seq_in_index",
                String.class,
                tableName,
                indexName);
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private Long characterLength(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Long.class,
                tableName,
                columnName);
    }

    private String columnComment(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT column_comment FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }
}
