package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 账号群关系状态转换、乱序保护和租户隔离真库测试。 */
class AccountGroupMembershipStatusServiceDbTest extends DbTestBase {

    private static final String GROUP_JID = "120363-membership-status@g.us";
    private static final String PROTOCOL_ACCOUNT_ID = "android-membership-account";

    @Autowired
    private AccountGroupMembershipStatusService statusService;

    @Autowired
    private AccountGroupMembershipSnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void preciseNegativeEventCreatesRetainedRowAndNewerPresenceRestoresMembership() {
        long accountId = seedAccount(PROTOCOL_ACCOUNT_ID);

        apply(accountId, "remove", 2000L, "evt-remove");

        assertThat(status(accountId, GROUP_JID)).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT.code());
        assertThat(joinedAt(accountId, GROUP_JID)).isNull();
        assertThat(lastSeenAt(accountId, GROUP_JID)).isNull();
        assertThat(deletedAt(accountId, GROUP_JID)).isNull();

        apply(accountId, "add", 1000L, "evt-old-add");
        assertThat(status(accountId, GROUP_JID)).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT.code());
        assertThat(joinedAt(accountId, GROUP_JID)).isNull();

        apply(accountId, "add", 3000L, "evt-new-add");
        assertThat(status(accountId, GROUP_JID)).isEqualTo(AccountGroupMembershipStatus.IN_GROUP.code());
        assertThat(joinedAt(accountId, GROUP_JID)).isEqualTo(3000L);
        assertThat(lastSeenAt(accountId, GROUP_JID)).isEqualTo(3000L);
    }

    @Test
    void preciseRemoveWinsOverSnapshotAtSameFactTime() {
        long accountId = seedAccount(PROTOCOL_ACCOUNT_ID + "-priority");
        apply(accountId, "remove", 4000L, "evt-remove-priority");

        snapshotService.replaceVisibleGroups(
                accountId,
                List.of(group(GROUP_JID)),
                true,
                4000L,
                "evt-snapshot-priority",
                "android_groups_dirty",
                ProtocolBackend.ANDROID);

        assertThat(status(accountId, GROUP_JID)).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT.code());
        assertThat(source(accountId, GROUP_JID)).isEqualTo("WGP2_REMOVE");
        assertThat(joinedAt(accountId, GROUP_JID)).isNull();
        assertThat(lastSeenAt(accountId, GROUP_JID)).isNull();
    }

    @Test
    void duplicateNegativeEventDoesNotInventPresenceTimestamps() {
        long accountId = seedAccount(PROTOCOL_ACCOUNT_ID + "-duplicate");
        apply(accountId, "leave", 5000L, "evt-leave");
        apply(accountId, "leave", 5000L, "evt-leave");

        assertThat(status(accountId, GROUP_JID)).isEqualTo(AccountGroupMembershipStatus.LEFT.code());
        assertThat(joinedAt(accountId, GROUP_JID)).isNull();
        assertThat(lastSeenAt(accountId, GROUP_JID)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM account_group_membership
                WHERE account_id = ? AND group_jid = ? AND deleted_at IS NULL
                """, Integer.class, accountId, GROUP_JID)).isOne();
    }

    @Test
    void batchStatusLookupReturnsOnlyCurrentTenantRows() {
        long sharedAccountId = 987654321L;
        long tenantOneLinkId = seedGroupLink(TEST_TENANT_ID, "tenant-one");
        long tenantTwoLinkId = seedGroupLink(2L, "tenant-two");
        seedMembership(TEST_TENANT_ID, sharedAccountId, tenantOneLinkId,
                AccountGroupMembershipStatus.KICKED_OUT, 6000L);
        seedMembership(2L, sharedAccountId, tenantTwoLinkId,
                AccountGroupMembershipStatus.LEFT, 7000L);
        AccountGroupMembershipLookup lookup = new AccountGroupMembershipLookup(sharedAccountId, GROUP_JID);

        assertThat(statusService.findCurrentStatuses(List.of(lookup))).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT));

        try {
            TenantContext.set(2L);
            assertThat(statusService.findCurrentStatuses(List.of(lookup))).singleElement()
                    .satisfies(row -> assertThat(row.status()).isEqualTo(AccountGroupMembershipStatus.LEFT));
        } finally {
            TenantContext.set(TEST_TENANT_ID);
        }
    }

    private void apply(long accountId, String action, long occurredAt, String eventId) {
        String protocolAccountId = jdbc.queryForObject(
                "SELECT protocol_account_id FROM account WHERE id = ?",
                String.class,
                accountId);
        statusService.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                TEST_TENANT_ID,
                accountId,
                protocolAccountId,
                GROUP_JID,
                action,
                occurredAt,
                eventId,
                "android_wgp2"));
    }

    private long seedAccount(String protocolAccountId) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, protocol_id, protocol_account_id,
                     group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, 'ANDROID', ?, 2, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "923" + now);
            ps.setString(3, protocolAccountId);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
    }

    private long seedGroupLink(long tenantId, String suffix) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, "wa://group/" + suffix + "-" + now + "@g.us");
            ps.setString(3, suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
    }

    private void seedMembership(long tenantId,
                                long accountId,
                                long groupLinkId,
                                AccountGroupMembershipStatus membershipStatus,
                                long statusUpdatedAt) {
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'TEST_FIXTURE', ?, ?, ?)
                """, tenantId, accountId, groupLinkId, GROUP_JID, membershipStatus.code(),
                statusUpdatedAt, statusUpdatedAt, statusUpdatedAt);
    }

    private static AccountGroupsReportedEvent.Group group(String groupJid) {
        return new AccountGroupsReportedEvent.Group(
                groupJid, "状态测试群", null, null, null, false, false, null);
    }

    private Integer status(long accountId, String groupJid) {
        return column(accountId, groupJid, "membership_status", Integer.class);
    }

    private String source(long accountId, String groupJid) {
        return column(accountId, groupJid, "status_source", String.class);
    }

    private Long joinedAt(long accountId, String groupJid) {
        return column(accountId, groupJid, "joined_at", Long.class);
    }

    private Long lastSeenAt(long accountId, String groupJid) {
        return column(accountId, groupJid, "last_seen_at", Long.class);
    }

    private Long deletedAt(long accountId, String groupJid) {
        return column(accountId, groupJid, "deleted_at", Long.class);
    }

    private <T> T column(long accountId, String groupJid, String column, Class<T> type) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM account_group_membership "
                        + "WHERE account_id = ? AND group_jid = ? AND deleted_at IS NULL",
                type,
                accountId,
                groupJid);
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, keys);
        Number key = keys.getKey();
        assertThat(key).as("generated key for " + sql).isNotNull();
        return key.longValue();
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }
}
