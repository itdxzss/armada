package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 群组模型 V120 只建立六张权威表，不夹带迁移、切流或过程设施。 */
class GroupDataModelFoundationMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V120__group_data_model_foundation.sql");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "wa_group",
            "wa_group_profile",
            "wa_group_invite",
            "wa_group_participant",
            "wa_account_group_binding",
            "account_group_sync_state");

    @Test
    void migrationCreatesExactlyTheSixApprovedAuthorityTables() throws IOException {
        assertThat(createdTables(sql())).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void migrationIsAdditiveSchemaOnly() throws IOException {
        assertThat(sql())
                .doesNotContainIgnoringCase(
                        "ALTER TABLE group_link",
                        "ALTER TABLE account_group_membership",
                        "INSERT INTO",
                        "UPDATE ",
                        "DELETE FROM",
                        "DROP TABLE",
                        "group_snapshot_effect_outbox",
                        "group_model_migration_run",
                        "protocol_command_outbox",
                        "marketing_task_send_attempt",
                        "field_version_keys",
                        "_version_key",
                        "membership_epoch",
                        "country_resolution_version",
                        "pool_hidden_at",
                        "last_check_error_domain",
                        "group_status");
    }

    @Test
    void migrationDefinesIdentityAndSafetyConstraints() throws IOException {
        assertThat(sql())
                .contains("uq_wa_group_jid (tenant_id, group_jid)")
                .contains("uq_wa_group_profile_group (tenant_id, group_id)")
                .contains("uq_wa_group_invite_code (tenant_id, invite_code)")
                .contains("uq_wa_group_participant_pn (tenant_id, group_id, pn_jid)")
                .contains("uq_wa_group_participant_lid (tenant_id, group_id, lid_jid)")
                .contains("uq_wa_account_group_binding (tenant_id, account_id, group_id)")
                .contains("uq_account_group_sync_state (tenant_id, account_id)")
                .contains("membership_active_since_at BIGINT DEFAULT NULL")
                .contains("first_post_control_observed_at BIGINT DEFAULT NULL")
                .contains("CHECK (was_in_initial_baseline IS NULL OR was_in_initial_baseline IN (0, 1))");
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static Set<String> createdTables(String sql) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        java.util.HashSet<String> tables = new java.util.HashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        return tables;
    }
}
