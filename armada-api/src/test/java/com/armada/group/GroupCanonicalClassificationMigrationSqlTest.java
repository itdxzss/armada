package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.enums.GroupClassification;
import com.armada.group.model.enums.GroupClassificationSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V140 canonical 群唯一分类 schema、回填与 dry-run 合同测试。 */
class GroupCanonicalClassificationMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V140__group_canonical_first_classification.sql");
    private static final Path DRY_RUN = Path.of(
            "../docs/operations/group-canonical-classification-dry-run.sql");

    @Test
    void stableEnumCodesRoundTrip() {
        assertThat(GroupClassification.UNCLASSIFIED.code()).isZero();
        assertThat(GroupClassification.HISTORICAL.code()).isOne();
        assertThat(GroupClassification.POST_CONTROL.code()).isEqualTo(2);
        assertThat(GroupClassification.fromCode(2))
                .isEqualTo(GroupClassification.POST_CONTROL);

        assertThat(GroupClassificationSource.BASELINE_CAPTURED.code()).isOne();
        assertThat(GroupClassificationSource.POST_CONTROL_DISCOVERED.code()).isEqualTo(2);
        assertThat(GroupClassificationSource.MIGRATION_EVIDENCE.code()).isEqualTo(3);
        assertThat(GroupClassificationSource.MIGRATION_LEGACY_FALLBACK.code()).isEqualTo(4);
        assertThat(GroupClassificationSource.fromCode(4))
                .isEqualTo(GroupClassificationSource.MIGRATION_LEGACY_FALLBACK);
    }

    @Test
    void migrationAddsNumericCanonicalHeaderWithConsistencyChecks() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("group_classification TINYINT NOT NULL DEFAULT 0")
                .contains("group_classified_at BIGINT DEFAULT NULL")
                .contains("group_classification_source TINYINT DEFAULT NULL")
                .contains("group_classification IN (0, 1, 2)")
                .contains("group_classification_source IN (1, 2, 3, 4)")
                .contains("group_classification = 0")
                .contains("group_classified_at IS NULL")
                .contains("group_classification_source IS NULL")
                .contains("group_classification IN (1, 2)")
                .contains("group_classified_at IS NOT NULL")
                .contains("group_classification_source IS NOT NULL")
                .contains("information_schema.columns")
                .contains("information_schema.statistics")
                .contains("information_schema.table_constraints");
    }

    @Test
    void migrationBackfillUsesEarliestReliableFactsAndHistoricalAmbiguityFallback()
            throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS wa_group_classification_migration_audit")
                .contains("baseline_captured_at")
                .contains("first_post_control_observed_at")
                .contains("resolution.post_control_evidence_at < "
                        + "resolution.historical_evidence_at")
                .contains("AMBIGUOUS_BOTH_HISTORICAL")
                .contains("EARLIEST_RELIABLE_FACT")
                .contains("MIGRATION_EVIDENCE")
                .contains("MIGRATION_LEGACY_FALLBACK")
                .doesNotContain("joined_at");
    }

    @Test
    void migrationBindsDeterministicAccountSyncHandlesBeforeCanonicalReadsSwitchOver()
            throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("UPDATE group_link handle")
                .contains("handle.link_url = CONCAT('wa://group/', current_group.group_jid)")
                .contains("SET handle.group_id = current_group.id")
                .contains("WHERE handle.group_id IS NULL");
    }

    @Test
    void readOnlyDryRunReportsBucketsAndAmbiguousGroupIds() throws IOException {
        String sql = Files.readString(DRY_RUN, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("resolution_rule")
                .contains("resolved_classification")
                .contains("COUNT(*) AS group_count")
                .contains("AMBIGUOUS_BOTH_HISTORICAL")
                .contains("group_id")
                .contains("tenant_id")
                .contains("deterministic_handle_bindings_to_backfill")
                .doesNotContain("UPDATE wa_group")
                .doesNotContain("INSERT INTO wa_group")
                .doesNotContain("CREATE TEMPORARY TABLE");
    }

    private static String migrationSql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
