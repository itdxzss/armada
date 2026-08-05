package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 群执行账号选号真库测试:验证在线/在群过滤、管理员优先与租户隔离。 */
class GroupExecutionAccountSelectorDbTest extends DbTestBase {

    @Autowired
    private GroupExecutionAccountSelector selector;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findSelectsActiveOnlineAdminAndIsolatesTenant() {
        long now = System.currentTimeMillis();
        long groupLinkId = seedGroupLink(now);
        long ordinaryAccountId = seedAccount("923310000001", 1, now);
        long adminAccountId = seedAccount("923310000002", 1, now);
        long offlineAdminAccountId = seedAccount("923310000003", 2, now);
        long deletedAdminAccountId = seedAccount("923310000004", 1, now);
        long kickedAdminAccountId = seedAccount("923310000005", 1, now);

        seedMembership(ordinaryAccountId, groupLinkId, false, now, null);
        seedMembership(adminAccountId, groupLinkId, true, now - 10_000, null);
        seedMembership(offlineAdminAccountId, groupLinkId, true, now + 20_000, null);
        seedMembership(deletedAdminAccountId, groupLinkId, true, now + 30_000, now + 31_000);
        seedMembership(kickedAdminAccountId, groupLinkId, true, now + 40_000, null);
        jdbc.update("""
                UPDATE account_group_membership
                SET membership_status = 3, status_source = 'TEST_KICKED', status_updated_at = ?
                WHERE account_id = ? AND group_jid = ? AND deleted_at IS NULL
                """, now + 40_000, kickedAdminAccountId, "120363selector@g.us");

        Optional<GroupExecutionAccount> selected = selector.find(groupLinkId);

        assertThat(selected).contains(new GroupExecutionAccount(
                adminAccountId, null, "acc_923310000002", "923310000002", true));

        try {
            TenantContext.set(2L);
            assertThat(selector.find(groupLinkId)).isEmpty();
        } finally {
            TenantContext.set(TEST_TENANT_ID);
        }
    }

    private long seedGroupLink(long now) {
        return insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, '选号测试群', 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "wa://group/selector-" + now + "@g.us");
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private long seedAccount(String phone, int loginState, long now) {
        long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, protocol_account_id,
                     priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setString(3, "acc_" + phone);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, created_at, updated_at)
                VALUES (?, ?, 2, ?, 1, ?, ?)
                """, TEST_TENANT_ID, accountId, loginState, now, now);
        return accountId;
    }

    private void seedMembership(
            long accountId,
            long groupLinkId,
            boolean admin,
            long lastSeenAt,
            Long deletedAt) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, is_admin,
                     membership_status, status_source, status_updated_at,
                     last_seen_at, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, "120363selector@g.us", admin,
                now, lastSeenAt, now, now, deletedAt);
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
