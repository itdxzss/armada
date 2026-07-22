package com.armada.group.mapper;

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
    void selectGroupExecutionAccount_prefersOnlineAdminThenMostRecentlySeen() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupExecutionAccount\""));
        assertTrue(xml.contains("s.login_state = #{onlineLoginState}"));
        assertTrue(xml.contains("m.deleted_at IS NULL"));
        assertTrue(xml.contains("m.membership_status IN (1, 2)"));
        assertTrue(xml.contains("a.deleted_at IS NULL"));
        assertTrue(xml.contains("ORDER BY COALESCE(m.is_admin, 0) DESC, COALESCE(m.last_seen_at, 0) DESC, m.id ASC"));
        assertTrue(xml.contains("LIMIT 1"));
    }

    @Test
    void snapshotEstablishedGroupsExcludePendingPreciseAddSource() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectSnapshotEstablishedGroupJids\""));
        assertTrue(xml.contains("COALESCE(status_source, '') &lt;&gt; 'WGP2_ADD'"));
    }

    @Test
    void groupLinkLookupByJidPrefersActiveButCanReviveArchivedEntry() throws IOException {
        String xml = mapperXml();
        assertTrue(xml.contains("<select id=\"selectGroupLinkIdByGroupJidIncludingDeleted\""));
        assertTrue(xml.contains("ORDER BY CASE WHEN g.deleted_at IS NULL THEN 0 ELSE 1 END, g.id ASC"));
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
