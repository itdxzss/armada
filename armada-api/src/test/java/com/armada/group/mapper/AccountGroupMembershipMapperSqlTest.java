package com.armada.group.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountGroupMembershipMapperSqlTest {

    @Test
    void upsertMembership_preservesKnownAdminWhenLightweightSyncOmitsIt() throws IOException {
        String xml = mapperXml();
        assertTrue(
                xml.contains("THEN COALESCE(VALUES(is_admin), account_group_membership.is_admin)"),
                "lightweight sync must not erase a previously known admin flag");
    }

    @Test
    void membershipRoleAndQuerySourcesHaveDeterministicPriority() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("WHEN 'WGP2_REMOVE' THEN 5"));
        assertTrue(xml.contains("WHEN 'WGP2_LEAVE' THEN 5"));
        assertTrue(xml.contains("WHEN 'WGP2_PROMOTE' THEN 4"));
        assertTrue(xml.contains("WHEN 'WGP2_DEMOTE' THEN 4"));
        assertTrue(xml.contains("WHEN 'WGP2_ADD' THEN 3"));
        assertTrue(xml.contains("WHEN 'GROUP_MEMBER_QUERY' THEN 2"));
        assertTrue(xml.contains("WHEN 'GROUP_SNAPSHOT' THEN 1"));
    }

    @Test
    void upsertMembership_preservesLatestExactExitAfterAccountRejoins() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("last_exit_type, last_exited_at"));
        assertTrue(xml.contains("WHEN VALUES(membership_status) IN (3, 4, 5)"));
        assertTrue(xml.contains("VALUES(last_exited_at) &gt;= account_group_membership.last_exited_at"));
        assertTrue(xml.contains("ELSE account_group_membership.last_exit_type"));
        assertTrue(xml.contains("ELSE account_group_membership.last_exited_at"));
        assertTrue(xml.contains("WHEN #{membershipStatus} IN (3, 4, 5)"));
        assertTrue(xml.contains("#{lastExitedAt} &gt;= last_exited_at"));
    }

    @Test
    void selectGroupAdminExecutionAccountsEnforcesAdminRoleInsteadOfOnlyPreferringIt() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupAdminExecutionAccounts\""));
        // 刷新群邀请链接必须由群管理员执行；只靠 ORDER BY 优先会在候选轮换时选中普通成员。
        assertTrue(
                xml.contains("AND self_participant.role IN (2, 3)"),
                "group admin selection must filter on the canonical participant role");
    }

    @Test
    void selectGroupExecutionAccountsSupportRotationAndFreshAdminPhones() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupExecutionAccounts\""));
        assertTrue(xml.contains("<select id=\"selectGroupExecutionAccountsByPhones\""));
        assertTrue(xml.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(xml.contains("self_participant.presence_status = 1"));
        assertTrue(xml.contains("a.deleted_at IS NULL"));
        assertTrue(xml.contains("COALESCE(binding.last_observed_at, 0) DESC"));
        assertTrue(xml.contains("a.ws_phone IN"));
        assertTrue(xml.contains("LIMIT #{limit}"));
        assertFalse(xml.substring(
                xml.indexOf("<sql id=\"groupExecutionAccountColumnsAndJoins\""),
                xml.indexOf("</sql>", xml.indexOf(
                        "<sql id=\"groupExecutionAccountColumnsAndJoins\"")))
                .contains("account_group_membership"));
    }

    @Test
    void selectGroupOwnerExecutionAccountRequiresConfirmedOwnerAndExecutableState() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectGroupOwnerExecutionAccount\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start);
        String query = xml.substring(start, end);
        int sharedStart = xml.indexOf("<sql id=\"groupExecutionAccountColumnsAndJoins\"");
        int sharedEnd = xml.indexOf("</sql>", sharedStart);
        assertTrue(sharedStart >= 0 && sharedEnd > sharedStart);
        String sharedQuery = xml.substring(sharedStart, sharedEnd);

        assertTrue(query.contains("<include refid=\"groupExecutionAccountColumnsAndJoins\"/>"));
        assertTrue(query.contains("owner_preview.owner_phone IS NOT NULL"));
        assertTrue(query.contains("SUBSTRING_INDEX(TRIM(a.ws_phone), '@', 1)"));
        assertTrue(query.contains("SUBSTRING_INDEX(TRIM(owner_preview.owner_phone), '@', 1)"));
        assertFalse(query.contains("self_participant.role IN (2, 3)"));
        assertTrue(sharedQuery.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(sharedQuery.contains("s.account_state = #{normalAccountState}"));
        assertTrue(sharedQuery.contains("self_participant.presence_status = 1"));
    }

    @Test
    void pullTaskPromoterCandidatesAreTenantGroupAndPermissionScoped() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf(
                "<select id=\"selectPullTaskAdminPromoterCandidatesByTenant\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start);
        String query = xml.substring(start, end);

        assertTrue(query.contains("a.tenant_id = #{tenantId}"));
        assertTrue(query.contains("current_group.group_jid = #{groupJid}"));
        assertTrue(query.contains("self_participant.presence_status = 1"));
        assertTrue(query.contains("self_participant.role IN (2, 3)"));
        assertTrue(query.contains("a.id &lt;&gt; #{managerAccountId}"));
        assertTrue(query.contains("a.protocol_id IS NOT NULL"));
        assertTrue(query.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(query.contains("s.account_state = #{normalAccountState}"));
        assertTrue(query.contains("(s.risk_status IS NULL OR s.risk_status = 1)"));
        assertTrue(query.contains("s.mute_status IS NULL"));
        assertTrue(query.contains("SUBSTRING_INDEX(legacy_preview.owner_phone, '@', 1)"));
        assertTrue(query.contains("COALESCE(binding.last_observed_at, 0) DESC"));
        assertFalse(query.contains("account_group_id = #{accountGroupId}"));
        assertFalse(query.contains("LIMIT 1"));
        assertFalse(query.contains("account_group_membership"));
    }

    @Test
    void pullTaskAdminDiscoveryRequiresInGroupHealthButNotAdminFlag() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf(
                "<select id=\"selectPullTaskAdminDiscoveryCandidatesByTenant\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start);
        String query = xml.substring(start, end);

        assertTrue(query.contains("a.tenant_id = #{tenantId}"));
        assertTrue(query.contains("current_group.group_jid = #{groupJid}"));
        assertTrue(query.contains("self_participant.presence_status = 1"));
        assertTrue(query.contains("a.id &lt;&gt; #{managerAccountId}"));
        assertTrue(query.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(query.contains("s.account_state = #{normalAccountState}"));
        assertTrue(query.contains("(s.risk_status IS NULL OR s.risk_status = 1)"));
        assertTrue(query.contains("s.mute_status IS NULL"));
        assertTrue(query.contains("ORDER BY a.id ASC"));
        assertTrue(query.contains("LIMIT #{limit}"));
        assertFalse(query.contains("AND self_participant.role IN (2, 3)"));
        assertFalse(query.contains("account_group_membership"));
    }

    @Test
    void snapshotEstablishedGroupsExcludePendingPreciseAddSource() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectSnapshotEstablishedGroupJids\""));
        assertTrue(xml.contains("COALESCE(status_source, '') &lt;&gt; 'WGP2_ADD'"));
    }

    @Test
    void currentStatusBatchReadsCanonicalBindingAndParticipant() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectCurrentStatuses\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start);
        String query = xml.substring(start, end);

        assertTrue(query.contains("FROM wa_account_group_binding binding"));
        assertTrue(query.contains("INNER JOIN wa_group_participant self_participant"));
        assertTrue(query.contains("CASE self_participant.presence_status"));
        assertTrue(query.contains("WHEN 'REMOVED' THEN 3"));
        assertTrue(query.contains("WHEN 'LEFT' THEN 4"));
        assertFalse(query.contains("FROM account_group_membership"));
    }

    @Test
    void groupLinkLookupByJidPrefersActiveButCanReviveArchivedEntry() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupLinkIdByGroupJidIncludingDeleted\""));
        assertTrue(xml.contains("ORDER BY CASE WHEN g.deleted_at IS NULL THEN 0 ELSE 1 END, g.id ASC"));
    }

    @Test
    void completeSnapshotMarksMissingRowsByOrderedPrimaryKeysInsteadOfAccountRangeUpdate()
            throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectMissingMembershipIds\" resultType=\"long\"> SELECT id"));
        assertTrue(xml.contains("<update id=\"markMembershipsNotInGroupByIds\">"));
        assertTrue(xml.contains("WHERE id IN <foreach collection=\"ids\""));
        assertTrue(xml.contains("status_updated_at &lt; #{row.statusUpdatedAt}"));
        assertTrue(xml.contains("ORDER BY id ASC"));
        assertFalse(xml.contains("<update id=\"markMissingMembershipsNotInGroup\">"));
    }

    private String mapperXml() throws IOException {
        String resource = "/mapper/group/AccountGroupMembershipMapper.xml";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertTrue(input != null, "missing mapper resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
        }
    }
}
