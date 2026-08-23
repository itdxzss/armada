package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V139 清理迁移后再次产生的 PN/LID 双行，并用群内 phone 唯一键阻止同类并发。 */
class GroupParticipantPhoneIdentityGuardMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V139__group_participant_phone_identity_guard.sql");

    @Test
    void migrationRepointsBindingsBeforeDeletingPnRowsAndBackfillsAfterDeletion()
            throws IOException {
        String sql = sql();
        int repoint = sql.indexOf("UPDATE wa_account_group_binding");
        int delete = sql.indexOf("DELETE participant");
        int backfill = sql.indexOf("SET canonical.pn_jid");

        assertThat(repoint).isGreaterThanOrEqualTo(0).isLessThan(delete);
        assertThat(delete).isGreaterThanOrEqualTo(0).isLessThan(backfill);
    }

    @Test
    void migrationOnlyMergesUnambiguousPairsBeforeAddingPhoneUniqueKey()
            throws IOException {
        String sql = sql();
        int guard = sql.indexOf("CREATE TEMPORARY TABLE tmp_participant_phone_guard");
        int merge = sql.indexOf("CREATE TEMPORARY TABLE tmp_participant_phone_merge_pair");
        int uniqueKey = sql.indexOf("ADD UNIQUE KEY uq_wa_group_participant_phone");

        assertThat(sql)
                .contains("INSERT INTO tmp_participant_phone_guard")
                .contains("OR COUNT(*) <> 2")
                .contains("HAVING COUNT(*) = 2")
                .contains("SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) = 1")
                .contains("SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) = 1")
                .contains("phone IS NOT NULL")
                .contains("information_schema.statistics");
        assertThat(guard).isGreaterThanOrEqualTo(0).isLessThan(merge);
        assertThat(merge).isGreaterThanOrEqualTo(0).isLessThan(uniqueKey);
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
