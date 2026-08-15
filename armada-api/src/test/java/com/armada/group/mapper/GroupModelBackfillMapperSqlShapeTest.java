package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群模型回填 MySQL 集合 SQL 的锁序和范围契约。 */
class GroupModelBackfillMapperSqlShapeTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupModelBackfillMapper.xml");

    @Test
    void groupBackfillUsesTenantScopedSortedInsertWithoutGapLockUpdate() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<select id=\"countInvalidGroupSources\"")
                .contains("<select id=\"countDuplicateGroupJids\"")
                .contains("<select id=\"countInviteConflicts\"")
                .contains("<select id=\"countParticipantConflicts\"")
                .contains("<select id=\"countBindingConflicts\"")
                .contains("GROUP BY preview.tenant_id, LOWER(TRIM(preview.group_jid))")
                .contains("HAVING COUNT(*) &gt; 1")
                .contains("<insert id=\"backfillGroups\"")
                .contains("INSERT INTO wa_group")
                .contains("preview.tenant_id = link.tenant_id")
                .contains("target.tenant_id = preview.tenant_id")
                .contains("GREATEST(link.updated_at, preview.updated_at) &gt;= target.updated_at")
                .contains("ORDER BY preview.tenant_id ASC, group_jid ASC")
                .contains("LIMIT #{limit}")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("VALUES(updated_at) &gt;= wa_group.updated_at")
                .contains("OR VALUES(created_at) &lt; wa_group.created_at")
                .contains("<insert id=\"backfillProfiles\"")
                .contains("INSERT INTO wa_group_profile")
                .contains("preview.group_created_at * 1000")
                .contains("COALESCE(health.current_count, preview.member_size)")
                .contains("WHEN VALUES(metadata_observed_at) IS NULL")
                .contains("<insert id=\"backfillMemberSnapshotHeaders\"")
                .contains("cache.snapshot_version AS member_snapshot_version")
                .contains("<insert id=\"backfillInvites\"")
                .contains("INSERT INTO wa_group_invite")
                .contains("WHEN VALUES(last_checked_at) IS NULL")
                .contains("<insert id=\"backfillCurrentInvitePointers\"")
                .contains("<insert id=\"backfillParticipants\"")
                .contains("INSERT INTO wa_group_participant")
                .contains("state.tenant_id = resolved_group.tenant_id")
                .contains("<insert id=\"backfillProfileOwners\"")
                .contains("preview.creator_country_iso2 AS phone_country_iso2")
                .contains("<insert id=\"backfillAccountParticipants\"")
                .contains("CONCAT(TRIM(account.ws_phone), '@s.whatsapp.net')")
                .contains("<insert id=\"backfillParticipantJoinFacts\"")
                .contains("<insert id=\"backfillParticipantExitFacts\"")
                .contains("<insert id=\"backfillAccountGroupBindings\"")
                .contains("INSERT INTO wa_account_group_binding")
                .contains("membership.joined_at AS membership_active_since_at")
                .contains("THEN 1 ELSE NULL END AS was_in_initial_baseline")
                .contains("<insert id=\"backfillAccountGroupSyncStates\"")
                .contains("INSERT INTO account_group_sync_state")
                .contains("WHEN account.group_baseline_state = 2 THEN 2")
                .contains("health.tenant_id = preview.tenant_id")
                .contains("invite.tenant_id = preview.tenant_id")
                .doesNotContain("FOR UPDATE",
                        "first_post_control_observed_at, created_at");

        String bindingSql = xml.substring(
                xml.indexOf("<insert id=\"backfillAccountGroupBindings\""),
                xml.indexOf("</insert>",
                        xml.indexOf("<insert id=\"backfillAccountGroupBindings\"")));
        assertThat(bindingSql)
                .contains("membership.joined_at AS membership_active_since_at")
                .doesNotContain("first_post_control_observed_at");
    }
}
