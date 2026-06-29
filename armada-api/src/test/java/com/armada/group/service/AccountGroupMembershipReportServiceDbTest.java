package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 账号当前群列表回填 DbTest:验证 baseline 差集、群入口入池和账号维度 membership。 */
class AccountGroupMembershipReportServiceDbTest extends DbTestBase {

    @Autowired
    private AccountGroupMembershipReportService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void applyGroupsReported_filtersBaselineUpsertsVisibleMembershipAndDeletesMissingMemberships() {
        long accountId = seedAccount("923300001001");
        String baselineJid = "120363baseline@g.us";
        String visibleJid = "120363visible@g.us";
        String staleJid = "120363stale@g.us";
        seedBaseline(accountId, "[\"" + baselineJid + "\"]");
        long staleGroupLinkId = seedExistingGroup(staleJid);
        seedMembership(accountId, staleGroupLinkId, staleJid);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                TEST_TENANT_ID,
                accountId,
                "acc_923300001001",
                1782626401000L,
                List.of(
                        new AccountGroupsReportedEvent.Group(
                                baselineJid, "上线前旧群", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                visibleJid, "上线后新群", 88, "861300000000@s.whatsapp.net",
                                null, true, false, "https://example.test/avatar.jpg")),
                "evt-groups-db-1"));

        assertThat(countMembership(accountId, baselineJid, true)).isZero();
        assertThat(countMembership(accountId, visibleJid, true)).isOne();
        assertThat(countMembership(accountId, staleJid, true)).isZero();
        assertThat(countMembership(accountId, staleJid, false)).isOne();
        assertThat(baselineJson(accountId)).isEqualTo("[\"" + baselineJid + "\"]");

        Long groupLinkId = jdbc.queryForObject("""
                SELECT g.id
                FROM group_link g
                JOIN group_link_preview p ON p.group_link_id = g.id
                WHERE p.group_jid = ?
                LIMIT 1
                """, Long.class, visibleJid);
        assertThat(groupLinkId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT link_url FROM group_link WHERE id = ?", String.class, groupLinkId))
                .isEqualTo("wa://group/" + visibleJid);
        assertThat(jdbc.queryForObject("SELECT origin FROM group_link WHERE id = ?", Integer.class, groupLinkId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT membership_state FROM group_link WHERE id = ?", Integer.class,
                groupLinkId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT wa_subject FROM group_link_preview WHERE group_link_id = ?",
                String.class, groupLinkId)).isEqualTo("上线后新群");
        assertThat(jdbc.queryForObject("SELECT member_size FROM group_link_preview WHERE group_link_id = ?",
                Integer.class, groupLinkId)).isEqualTo(88);
        assertThat(jdbc.queryForObject("SELECT is_admin FROM account_group_membership WHERE account_id = ? "
                        + "AND group_jid = ? AND deleted_at IS NULL",
                Boolean.class, accountId, visibleJid)).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_count FROM group_link_health WHERE group_link_id = ?",
                Integer.class, groupLinkId)).isEqualTo(88);
    }

    @Test
    void applyGroupsReported_preservesExistingHealthCountWhenReportOmitsMemberCount() {
        long accountId = seedAccount("923300001002");
        String visibleJid = "120363visible-count@g.us";
        seedBaseline(accountId, "[]");
        long groupLinkId = seedExistingGroup(visibleJid);
        seedHealth(groupLinkId, 55);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                TEST_TENANT_ID,
                accountId,
                "acc_923300001002",
                1782626401000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        visibleJid, "未带人数的群", null, null, null, false, false, null)),
                "evt-groups-db-2"));

        assertThat(jdbc.queryForObject("SELECT current_count FROM group_link_health WHERE group_link_id = ?",
                Integer.class, groupLinkId)).isEqualTo(55);
    }

    private long seedAccount(String phone) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, protocol_account_id,
                     group_baseline_state, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 2, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setString(3, "acc_" + phone);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
    }

    private void seedBaseline(long accountId, String baselineGroupJids) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, ?, JSON_LENGTH(?), ?, ?, ?)
                """, TEST_TENANT_ID, accountId, baselineGroupJids, baselineGroupJids, now, now, now);
    }

    private long seedExistingGroup(String groupJid) {
        long now = System.currentTimeMillis();
        long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 5, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "wa://group/" + groupJid);
            ps.setString(3, "旧同步群");
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "旧同步群", now, now);
        return groupLinkId;
    }

    private void seedMembership(long accountId, long groupLinkId, String groupJid) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now);
    }

    private void seedHealth(long groupLinkId, int currentCount) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, current_count,
                     last_check_at, health_failure_count, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, currentCount, now, now, now);
    }

    private int countMembership(long accountId, String groupJid, boolean activeOnly) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM account_group_membership
                WHERE account_id = ?
                  AND group_jid = ?
                  AND (? = 0 OR deleted_at IS NULL)
                """, Integer.class, accountId, groupJid, activeOnly ? 1 : 0);
    }

    private String baselineJson(long accountId) {
        return jdbc.queryForObject("SELECT baseline_group_jids FROM account_group_baseline WHERE account_id = ?",
                String.class, accountId);
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
