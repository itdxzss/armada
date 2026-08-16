package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTreeGroupVO;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 营销任务建任务抽屉的账号→可营销群树。
 *
 * <p>账号树首屏展示账号和库内当前关系数量；点击账号后只查本地账号群关系状态，
 * 不再调用协议层实时查询。</p>
 */
class MarketingTaskAccountTreeDbTest extends DbTestBase {

    private static final int BASELINE_PENDING = 1;
    private static final int BASELINE_CAPTURED = 2;
    private static final int BASELINE_DISABLED = 3;

    @Autowired
    private MarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accountTree_returnsChineseStatusAndDbGroupCount() {
        long accountGroupId = seedAccountGroup("tree-accounts-only");
        long accountId = seedAccount("923300000100", accountGroupId, BASELINE_CAPTURED, 2, 1, 1, null);
        String newJid = "120363count-new@g.us";
        seedBaseline(accountId, "[]");
        seedMembership(accountId, seedGroup("count-new", newJid, 1, 0), newJid);

        MarketingAccountTreeVO tree = service.accountTree(accountGroupId);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(accountId);
            assertThat(account.wsPhone()).isEqualTo("923300000100");
            assertThat(account.status()).isEqualTo("ONLINE");
            assertThat(account.statusText()).isEqualTo("在线");
            assertThat(account.groupCount()).isEqualTo(1);
            assertThat(account.selectable()).isTrue();
            assertThat(account.groupsError()).isFalse();
            assertThat(account.groups()).isEmpty();
        });
    }

    @Test
    void accountTree_includesOfflineAccountAsNotSelectable() {
        long accountGroupId = seedAccountGroup("tree-offline");
        long accountId = seedAccount("923300000105", accountGroupId, BASELINE_CAPTURED, 2, 2, 1, null);

        MarketingAccountTreeVO tree = service.accountTree(accountGroupId);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(accountId);
            assertThat(account.status()).isEqualTo("OFFLINE");
            assertThat(account.statusText()).isEqualTo("离线");
            assertThat(account.selectable()).isFalse();
            assertThat(account.disabledReason()).isEqualTo("离线");
        });
    }

    @Test
    void accountGroups_pendingBaselineDoesNotReadCurrentMembership() {
        long accountGroupId = seedAccountGroup("tree-pending");
        long accountId = seedAccount("923300000101", accountGroupId, BASELINE_PENDING, 2, 1, 1, null);
        String oldJid = "120363pending-old@g.us";
        seedMembership(accountId, seedGroup("pending-old", oldJid, 1, 0), oldJid);

        var account = service.accountGroups(accountId);

        assertThat(account).satisfies(node -> {
            assertThat(node.accountId()).isEqualTo(accountId);
            assertThat(node.wsPhone()).isEqualTo("923300000101");
            assertThat(node.status()).isEqualTo("ONLINE");
            assertThat(node.statusText()).isEqualTo("在线");
            assertThat(node.selectable()).isFalse();
            assertThat(node.disabledReason()).isEqualTo("群同步中");
            assertThat(node.groupsError()).isFalse();
            assertThat(node.groups()).isEmpty();
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM account_group_baseline
                WHERE account_id = ?
                """, Integer.class, accountId)).isZero();
        assertThat(activeMembershipJids(accountId)).containsExactly(oldJid);
        Integer state = jdbc.queryForObject(
                "SELECT group_baseline_state FROM account WHERE id = ?",
                Integer.class,
                accountId);
        assertThat(state).isEqualTo(BASELINE_PENDING);
    }

    @Test
    void accountGroups_capturedBaselineShowsAllCurrentRelationshipRows() {
        long accountGroupId = seedAccountGroup("tree-captured");
        long accountId = seedAccount("923300000102", accountGroupId, BASELINE_CAPTURED, 2, 1, 1, null);
        String oldJid = "120363captured-old@g.us";
        String newJid = "120363captured-new@g.us";
        seedBaseline(accountId, "[\"" + oldJid + "\"]");
        seedMembership(accountId, seedGroup("captured-old", oldJid, 1, 0), oldJid);
        seedMembership(accountId, seedGroup("captured-new", newJid, 1, 0), newJid);

        var account = service.accountGroups(accountId);

        assertThat(account).satisfies(node -> {
            assertThat(node.groupsError()).isFalse();
            assertThat(node.groups()).extracting(MarketingTreeGroupVO::groupJid)
                    .containsExactly(oldJid, newJid);
            assertThat(node.groups()).filteredOn(group -> newJid.equals(group.groupJid())).singleElement()
                    .satisfies(group -> {
                assertThat(group.groupName()).isEqualTo("营销群-captured-new");
                assertThat(group.linkUrl()).isEqualTo("https://chat.whatsapp.com/captured-new");
            });
        });
        assertThat(activeMembershipJids(accountId)).containsExactly(oldJid, newJid);
        assertThat(baselineJson(accountId)).contains(oldJid);
    }

    @Test
    void accountGroups_baselineDisabledShowsAllDbGroups() {
        long accountGroupId = seedAccountGroup("tree-disabled");
        long accountId = seedAccount("923300000103", accountGroupId, BASELINE_DISABLED, 2, 1, 1, null);
        String oldJid = "120363disabled-old@g.us";
        seedBaseline(accountId, "[\"" + oldJid + "\"]");
        seedMembership(accountId, seedGroup("disabled-old", oldJid, 1, 0), oldJid);

        var account = service.accountGroups(accountId);

        assertThat(account).satisfies(node ->
                assertThat(node.groups()).extracting(MarketingTreeGroupVO::groupJid)
                        .containsExactly(oldJid));
        assertThat(activeMembershipJids(accountId)).containsExactly(oldJid);
    }

    @Test
    void accountGroups_groupHealthDoesNotHideCurrentRelationship() {
        long accountGroupId = seedAccountGroup("tree-failed");
        long accountId = seedAccount("923300000104", accountGroupId, BASELINE_CAPTURED, 2, 1, 1, null);
        String existingJid = "120363failed-existing@g.us";
        seedBaseline(accountId, "[]");
        seedMembership(accountId, seedGroup("failed-existing", existingJid, 2, 0), existingJid);

        var account = service.accountGroups(accountId);

        assertThat(account).satisfies(node -> {
            assertThat(node.groupsError()).isFalse();
            assertThat(node.groups()).extracting(MarketingTreeGroupVO::groupJid)
                    .containsExactly(existingJid);
        });
        assertThat(activeMembershipJids(accountId)).containsExactly(existingJid);
    }

    @Test
    void accountGroups_showsAllFiveMembershipStatesAndKeepsThemSelectable() {
        long accountGroupId = seedAccountGroup("tree-membership-statuses");
        long accountId = seedAccount(
                "923300000106", accountGroupId, BASELINE_DISABLED, 2, 1, 1, null);
        int index = 0;
        for (AccountGroupMembershipStatus status : AccountGroupMembershipStatus.values()) {
            String suffix = "membership-status-" + index;
            String groupJid = "120363-membership-status-" + index + "@g.us";
            seedMembership(accountId, seedGroup(suffix, groupJid, 1, 0), groupJid, status.code());
            index++;
        }

        MarketingAccountTreeVO tree = service.accountTree(accountGroupId);
        var account = service.accountGroups(accountId);

        assertThat(tree.accounts()).singleElement().satisfies(node -> {
            assertThat(node.groupCount()).isEqualTo(5);
            assertThat(node.selectable()).isTrue();
        });
        assertThat(account.selectable()).isTrue();
        assertThat(account.groups())
                .extracting(MarketingTreeGroupVO::membershipStatus)
                .containsExactly("IN_GROUP", "UNCONFIRMED", "KICKED_OUT", "LEFT", "NOT_IN_GROUP");
    }

    @Test
    void accountTree_emptyGroupIdReturnsEmptyTree() {
        assertThat(service.accountTree(null).accounts()).isEmpty();
    }

    private long seedAccountGroup(String suffix) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "营销账号组-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private long seedAccount(String phone,
                             long accountGroupId,
                             int baselineState,
                             int accountState,
                             int loginState,
                             int riskStatus,
                             Integer muteStatus) {
        long now = System.currentTimeMillis();
        long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     protocol_account_id, group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setString(4, "acc_" + phone);
            ps.setInt(5, baselineState);
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, accountState, loginState, riskStatus, muteStatus, now, now);
        return accountId;
    }

    private void seedBaseline(long accountId, String baselineGroupJids) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, ?, JSON_LENGTH(?), ?, ?, ?)
                """, TEST_TENANT_ID, accountId, baselineGroupJids, baselineGroupJids, now, now, now);
    }

    private long seedGroup(String suffix, String groupJid, Integer healthStatus, Integer banned) {
        long now = System.currentTimeMillis();
        long currentGroupId = insertAndReturnId("""
                INSERT INTO wa_group
                    (tenant_id, group_jid, display_name, origin, created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, groupJid);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO wa_group_profile
                    (tenant_id, group_id, subject, announce_only, health_status, banned,
                     created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?)
                """, TEST_TENANT_ID, currentGroupId, "WA群-" + suffix,
                healthStatus, banned, now, now);
        long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, group_id, link_url, group_name, origin,
                     membership_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 2, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, currentGroupId);
            ps.setString(3, "https://chat.whatsapp.com/" + suffix);
            ps.setString(4, "营销群-" + suffix);
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, announce_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "WA群-" + suffix, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, healthStatus, banned, now, now);
        return groupLinkId;
    }

    private void seedMembership(long accountId, long groupLinkId, String groupJid) {
        seedMembership(accountId, groupLinkId, groupJid, AccountGroupMembershipStatus.IN_GROUP.code());
    }

    private void seedMembership(long accountId, long groupLinkId, String groupJid, int membershipStatus) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'TEST_FIXTURE', ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, membershipStatus,
                now, now, now, now);
        long currentGroupId = jdbc.queryForObject(
                "SELECT group_id FROM group_link WHERE id = ?", Long.class, groupLinkId);
        String phone = jdbc.queryForObject(
                "SELECT ws_phone FROM account WHERE id = ?", String.class, accountId);
        int presenceStatus = switch (membershipStatus) {
            case 1 -> 1;
            case 2 -> 0;
            default -> 2;
        };
        String exitType = switch (membershipStatus) {
            case 3 -> "REMOVED";
            case 4 -> "LEFT";
            case 5 -> "UNKNOWN";
            default -> null;
        };
        long participantId = insertAndReturnId("""
                INSERT INTO wa_group_participant
                    (tenant_id, group_id, pn_jid, phone, presence_status,
                     presence_source, presence_observed_at, last_exit_type,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'TEST_FIXTURE', ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setLong(2, currentGroupId);
            ps.setString(3, phone + "@s.whatsapp.net");
            ps.setString(4, phone);
            ps.setInt(5, presenceStatus);
            ps.setLong(6, now);
            ps.setString(7, exitType);
            ps.setLong(8, now);
            ps.setLong(9, now);
        });
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                    (tenant_id, account_id, group_id, participant_id,
                     membership_active_since_at, last_observed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, currentGroupId, participantId,
                now, now, now, now);
    }

    private List<String> activeMembershipJids(long accountId) {
        return jdbc.queryForList("""
                SELECT group_jid
                FROM account_group_membership
                WHERE account_id = ? AND deleted_at IS NULL
                ORDER BY group_jid ASC
                """, String.class, accountId);
    }

    private String baselineJson(long accountId) {
        return jdbc.queryForObject("""
                SELECT baseline_group_jids
                FROM account_group_baseline
                WHERE account_id = ?
                """, String.class, accountId);
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
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }
}
