package com.armada.group.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountGroupMembershipMapperSqlTest {

    @Test
    void upsertMembership_preservesKnownAdminWhenLightweightSyncOmitsIt() throws IOException {
        String resource = "/mapper/group/AccountGroupMembershipMapper.xml";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertTrue(input != null, "missing mapper resource " + resource);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            assertTrue(
                    xml.contains("is_admin = COALESCE(VALUES(is_admin), account_group_membership.is_admin)"),
                    "lightweight sync must not erase a previously known admin flag");
        }
    }
}
