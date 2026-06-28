package com.armada.marketing.service;

import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTreeGroupVO;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 营销任务建任务抽屉的账号→可营销群树。
 *
 * <p>当前 armada 尚无 per-account 群成员表,因此本测试验证的是账号分组内在线可用账号
 * 与租户可用群池的组合,再按账号登录前基线 JSON 排除历史群。</p>
 */
class MarketingTaskAccountTreeDbTest extends DbTestBase {

    private static final int BASELINE_CAPTURED = 2;
    private static final int BASELINE_DISABLED = 3;

    @Autowired
    private MarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accountTree_filtersOfflineAccountsAndBaselineGroups() {
        long accountGroupId = seedAccountGroup("tree-filter");
        long onlineAccountId = seedAccount("923300000001", accountGroupId, BASELINE_CAPTURED, 2, 1, 1, null);
        seedAccount("923300000002", accountGroupId, BASELINE_CAPTURED, 2, 2, 1, null);
        seedAccount("923300000003", accountGroupId, BASELINE_CAPTURED, 3, 1, 1, null);
        String oldJid = "120363000001@g.us";
        String newJid = "120363000002@g.us";
        String bannedJid = "120363000003@g.us";
        seedBaseline(onlineAccountId, "[\"" + oldJid + "\"]");
        seedGroup("old", oldJid, 1, 0);
        seedGroup("new", newJid, 1, 0);
        seedGroup("banned", bannedJid, 1, 1);

        MarketingAccountTreeVO tree = service.accountTree(accountGroupId);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(onlineAccountId);
            assertThat(account.wsPhone()).isEqualTo("923300000001");
            assertThat(account.status()).isEqualTo("ONLINE");
            assertThat(account.groupsError()).isFalse();
            assertThat(account.groups()).extracting(MarketingTreeGroupVO::groupJid)
                    .containsExactly(newJid);
            assertThat(account.groups()).singleElement().satisfies(group -> {
                assertThat(group.groupName()).isEqualTo("营销群-new");
                assertThat(group.linkUrl()).isEqualTo("https://chat.whatsapp.com/new");
                assertThat(group.isAdmin()).isFalse();
            });
        });
    }

    @Test
    void accountTree_baselineDisabledAccountDoesNotExcludeGroups() {
        long accountGroupId = seedAccountGroup("tree-disabled");
        long accountId = seedAccount("923300000004", accountGroupId, BASELINE_DISABLED, 2, 1, 1, null);
        String oldJid = "120363000004@g.us";
        seedBaseline(accountId, "[\"" + oldJid + "\"]");
        seedGroup("disabled-old", oldJid, 1, 0);

        MarketingAccountTreeVO tree = service.accountTree(accountGroupId);

        assertThat(tree.accounts()).singleElement().satisfies(account ->
                assertThat(account.groups()).extracting(MarketingTreeGroupVO::groupJid)
                        .containsExactly(oldJid));
    }

    @Test
    void accountTree_emptyGroupId_returnsEmptyTree() {
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
                     group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setInt(4, baselineState);
            ps.setLong(5, now);
            ps.setLong(6, now);
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

    private void seedGroup(String suffix, String groupJid, Integer healthStatus, Integer banned) {
        long now = System.currentTimeMillis();
        long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 2, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "https://chat.whatsapp.com/" + suffix);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
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
