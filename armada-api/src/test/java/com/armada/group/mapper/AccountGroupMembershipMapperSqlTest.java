package com.armada.group.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 账号群关系 Mapper 当前模型 SQL 结构门禁。 */
class AccountGroupMembershipMapperSqlTest {

    @Test
    void executionAccountQueriesUseCanonicalBindingAndParticipant() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupAdminExecutionAccounts\""));
        assertTrue(xml.contains("AND self_participant.role IN (2, 3)"));
        assertTrue(xml.contains("<select id=\"selectGroupExecutionAccountsByPhones\""));
        assertTrue(xml.contains("self_participant.presence_status = 1"));
        assertTrue(xml.contains("COALESCE(binding.last_observed_at, 0) DESC"));
        assertFalse(xml.contains("FROM account_group_membership"));
    }

    @Test
    void ownerExecutionKeepsCreatorPhoneCompatibility() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectGroupOwnerExecutionAccount\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start);
        String query = xml.substring(start, end);
        assertTrue(query.contains("owner_preview.owner_phone IS NOT NULL"));
        assertTrue(query.contains("SUBSTRING_INDEX(TRIM(owner_preview.owner_phone), '@', 1)"));
        assertFalse(query.contains("self_participant.role IN (2, 3)"));
    }

    @Test
    void pullTaskCandidatesAreTenantGroupAndCurrentPermissionScoped() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectPullTaskAdminPromoterCandidatesByTenant\""));
        assertTrue(xml.contains("current_group.group_jid = #{groupJid}"));
        assertTrue(xml.contains("self_participant.role IN (2, 3)"));
        assertTrue(xml.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(xml.contains("s.account_state IN"));
        assertTrue(xml.contains("collection=\"executableAccountStates\""));
        assertTrue(xml.contains("<select id=\"selectPullTaskAdminDiscoveryCandidatesByTenant\""));
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
        assertFalse(query.contains("FROM account_group_membership"));
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
