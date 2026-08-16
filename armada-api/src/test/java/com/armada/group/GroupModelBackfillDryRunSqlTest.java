package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 六表回填前只读门禁 SQL 的结构契约。 */
class GroupModelBackfillDryRunSqlTest {

    private static final Path DRY_RUN = Path.of(
            "../docs/operations/group-data-model-backfill-dry-run.sql");

    @Test
    void dryRunIsReadOnlyAndCoversDestructiveMigrationRisks() throws IOException {
        String sql = Files.readString(DRY_RUN, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("duplicate_group_jid")
                .contains("duplicate_invite_code")
                .contains("invite_group_conflict")
                .contains("orphan_membership")
                .contains("unresolved_binding_target")
                .contains("unresolved_member_group")
                .contains("invalid_participant_jid")
                .contains("FROM whatsapp_group_member_snapshot")
                .contains("invalid_binding_account_phone")
                .contains("ambiguous_empty_baseline")
                .contains("baseline_count_mismatch")
                .contains("unresolved_baseline_target")
                .contains("duplicate_baseline_group")
                .contains("legacy_joined_at_non_null")
                .contains("existing_first_post_non_null")
                .contains("baseline_first_post_non_null")
                .doesNotContain("migrated_first_post_non_null")
                .doesNotContainIgnoringCase(
                        "INSERT ", "UPDATE ", "DELETE ", "ALTER ", "DROP ",
                        "CREATE ", "REPLACE ", "TRUNCATE ", "CALL ", "FOR UPDATE");
    }

    @Test
    void everyCrossTableJoinIncludesTenantIdentity() throws IOException {
        String sql = Files.readString(DRY_RUN, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("preview.tenant_id = link.tenant_id")
                .contains("account.tenant_id = membership.tenant_id")
                .contains("link.tenant_id = membership.tenant_id")
                .contains("baseline.tenant_id = account.tenant_id")
                .contains("wa_group.tenant_id = membership.tenant_id");
    }
}
