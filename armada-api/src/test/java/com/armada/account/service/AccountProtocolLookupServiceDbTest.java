package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.testsupport.DbTestBase;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 账号协议引用查询真库测试：锁定租户、软删、状态筛选和协议身份完整性。 */
class AccountProtocolLookupServiceDbTest extends DbTestBase {

    private static final long OTHER_TENANT_ID = 2L;
    private static final int RISK_ALLOWED = 1;
    private static final int RISK_BLOCKED = 2;
    private static final int MUTE_SIX_HOURS = 1;

    @Autowired
    private AccountProtocolLookupService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findActiveProtocolRefReturnsOfflineAccountAndRejectsDeletedOrOtherTenantRows() {
        long now = System.currentTimeMillis();
        long offlineId = seedAccount(TEST_TENANT_ID, phone(now, "01"), null,
                "ANDROID", "android-offline", null, now);
        seedState(TEST_TENANT_ID, offlineId, AccountStateCode.NORMAL,
                AccountLoginStateCode.OFFLINE, RISK_ALLOWED, null, now);
        long deletedId = seedAccount(TEST_TENANT_ID, phone(now, "02"), null,
                "WEB", "web-deleted", now, now);
        long foreignId = seedAccount(OTHER_TENANT_ID, phone(now, "03"), null,
                "WEB", "web-foreign", null, now);

        assertThat(service.findActiveProtocolRef(offlineId))
                .contains(new ProtocolAccountRef(
                        offlineId, ProtocolBackend.ANDROID, "android-offline", phone(now, "01")));
        assertThat(service.findActiveProtocolRef(deletedId)).isEmpty();
        assertThat(service.findActiveProtocolRef(foreignId)).isEmpty();
    }

    @Test
    void findActiveProtocolRefTreatsNullProtocolIdAsLegacyWeb() {
        long now = System.currentTimeMillis();
        String legacyPhone = phone(now, "04");
        long legacyWebId = seedAccount(TEST_TENANT_ID, legacyPhone, null,
                null, "web-legacy-null-protocol", null, now);

        assertThat(service.findActiveProtocolRef(legacyWebId))
                .contains(new ProtocolAccountRef(
                        legacyWebId,
                        ProtocolBackend.WEB,
                        "web-legacy-null-protocol",
                        legacyPhone));
    }

    @Test
    void findRandomOnlineNormalByGroupIdUsesOnlyEligibleGroupAccountButIgnoresOccupancy() {
        long now = System.currentTimeMillis();
        long groupId = seedAccountGroup(TEST_TENANT_ID, "lookup-" + now, now);
        long otherGroupId = seedAccountGroup(TEST_TENANT_ID, "lookup-other-" + now, now);
        long foreignOnlyGroupId = seedAccountGroup(TEST_TENANT_ID, "lookup-foreign-only-" + now, now);
        long eligibleId = seedAccount(TEST_TENANT_ID, phone(now, "11"), groupId,
                "WEB", "web-eligible", null, now);
        seedState(TEST_TENANT_ID, eligibleId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, null, null, now);
        seedOccupancy(TEST_TENANT_ID, eligibleId, now);

        long offlineId = seedAccount(TEST_TENANT_ID, phone(now, "12"), groupId,
                "WEB", "web-offline", null, now);
        seedState(TEST_TENANT_ID, offlineId, AccountStateCode.NORMAL,
                AccountLoginStateCode.OFFLINE, RISK_ALLOWED, null, now);
        long abnormalId = seedAccount(TEST_TENANT_ID, phone(now, "13"), groupId,
                "WEB", "web-abnormal", null, now);
        seedState(TEST_TENANT_ID, abnormalId, AccountStateCode.RESTRICTED,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long riskId = seedAccount(TEST_TENANT_ID, phone(now, "14"), groupId,
                "WEB", "web-risk", null, now);
        seedState(TEST_TENANT_ID, riskId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_BLOCKED, null, now);
        long mutedId = seedAccount(TEST_TENANT_ID, phone(now, "15"), groupId,
                "WEB", "web-muted", null, now);
        seedState(TEST_TENANT_ID, mutedId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, MUTE_SIX_HOURS, now);
        long incompleteId = seedAccount(TEST_TENANT_ID, phone(now, "16"), groupId,
                null, "web-incomplete", null, now);
        seedState(TEST_TENANT_ID, incompleteId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long otherGroupAccountId = seedAccount(TEST_TENANT_ID, phone(now, "17"), otherGroupId,
                "WEB", "web-other-group", null, now);
        seedState(TEST_TENANT_ID, otherGroupAccountId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long foreignId = seedAccount(OTHER_TENANT_ID, phone(now, "18"), foreignOnlyGroupId,
                "WEB", "web-foreign", null, now);
        seedState(OTHER_TENANT_ID, foreignId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);

        assertThat(service.findRandomOnlineNormalByGroupId(groupId))
                .contains(new ProtocolAccountRef(
                        eligibleId, ProtocolBackend.WEB, "web-eligible", phone(now, "11")));
        assertThat(service.findRandomOnlineNormalByGroupId(Long.MAX_VALUE)).isEmpty();
        assertThat(service.findRandomOnlineNormalByGroupId(foreignOnlyGroupId)).isEmpty();
    }

    @Test
    void findRandomOnlineNormalWebByGroupIdExcludesAndroidFromMixedAndAndroidOnlyGroups() {
        long now = System.currentTimeMillis();
        long mixedGroupId = seedAccountGroup(TEST_TENANT_ID, "lookup-web-mixed-" + now, now);
        long androidOnlyGroupId = seedAccountGroup(TEST_TENANT_ID, "lookup-android-only-" + now, now);
        long legacyWebOnlyGroupId = seedAccountGroup(TEST_TENANT_ID, "lookup-web-legacy-" + now, now);
        long androidId = seedAccount(TEST_TENANT_ID, phone(now, "31"), mixedGroupId,
                "ANDROID", "android-mixed", null, now);
        seedState(TEST_TENANT_ID, androidId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long webId = seedAccount(TEST_TENANT_ID, phone(now, "32"), mixedGroupId,
                "WEB", "web-mixed", null, now);
        seedState(TEST_TENANT_ID, webId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long androidOnlyId = seedAccount(TEST_TENANT_ID, phone(now, "33"), androidOnlyGroupId,
                "ANDROID", "android-only", null, now);
        seedState(TEST_TENANT_ID, androidOnlyId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);
        long legacyWebId = seedAccount(TEST_TENANT_ID, phone(now, "34"), legacyWebOnlyGroupId,
                null, "web-legacy", null, now);
        seedState(TEST_TENANT_ID, legacyWebId, AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, RISK_ALLOWED, null, now);

        assertThat(service.findRandomOnlineNormalWebByGroupId(mixedGroupId))
                .contains(new ProtocolAccountRef(
                        webId, ProtocolBackend.WEB, "web-mixed", phone(now, "32")));
        assertThat(service.findRandomOnlineNormalWebByGroupId(androidOnlyGroupId)).isEmpty();
        assertThat(service.findRandomOnlineNormalWebByGroupId(legacyWebOnlyGroupId))
                .contains(new ProtocolAccountRef(
                        legacyWebId, ProtocolBackend.WEB, "web-legacy", phone(now, "34")));
    }

    @Test
    void findActiveProtocolRefsByPhonesNormalizesInputAndReturnsOfflineCompleteAccountsOnly() {
        long now = System.currentTimeMillis();
        String offlinePhone = phone(now, "21");
        String completePhone = phone(now, "22");
        String incompletePhone = phone(now, "23");
        String foreignPhone = phone(now, "24");
        long offlineId = seedAccount(TEST_TENANT_ID, offlinePhone, null,
                "ANDROID", "android-batch-offline", null, now);
        seedState(TEST_TENANT_ID, offlineId, AccountStateCode.NORMAL,
                AccountLoginStateCode.OFFLINE, RISK_ALLOWED, MUTE_SIX_HOURS, now);
        long completeId = seedAccount(TEST_TENANT_ID, completePhone, null,
                "WEB", "web-batch", null, now);
        long incompleteId = seedAccount(TEST_TENANT_ID, incompletePhone, null,
                "", "web-incomplete", null, now);
        seedAccount(OTHER_TENANT_ID, foreignPhone, null,
                "WEB", "web-foreign", null, now);

        Map<String, ProtocolAccountRef> refs = service.findActiveProtocolRefsByPhones(Arrays.asList(
                "  " + offlinePhone + "  ", "", null, offlinePhone, completePhone,
                incompletePhone, foreignPhone));

        assertThat(refs).containsExactly(
                Map.entry(offlinePhone, new ProtocolAccountRef(
                        offlineId, ProtocolBackend.ANDROID, "android-batch-offline", offlinePhone)),
                Map.entry(completePhone, new ProtocolAccountRef(
                        completeId, ProtocolBackend.WEB, "web-batch", completePhone)));
        assertThat(refs).doesNotContainKeys(incompletePhone, foreignPhone);
        assertThat(service.findActiveProtocolRefsByPhones(List.of())).isEmpty();
        assertThat(service.findActiveProtocolRefsByPhones(null)).isEmpty();
    }

    @Test
    void randomSelectorSqlDoesNotJoinAnyOccupancyTable() throws Exception {
        String xml = new ClassPathResource("mapper/account/AccountMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);
        String selector = xml.substring(
                xml.indexOf("<select id=\"selectRandomOnlineNormalByGroupId\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectRandomOnlineNormalByGroupId\"")));

        assertThat(selector).doesNotContainIgnoringCase("occupancy");
        assertThat(selector).contains("ORDER BY RAND()");
    }

    private long seedAccount(
            long tenantId,
            String phone,
            Long accountGroupId,
            String protocolId,
            String protocolAccountId,
            Long deletedAt,
            long now) {
        return insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id,
                     protocol_id, protocol_account_id, priority, created_at, updated_at, deleted_at)
                VALUES (?, ?, 1, 1, ?, ?, ?, 0, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, phone);
            ps.setObject(3, accountGroupId);
            ps.setString(4, protocolId);
            ps.setString(5, protocolAccountId);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setObject(8, deletedAt);
        });
    }

    private void seedState(
            long tenantId,
            long accountId,
            Integer accountState,
            Integer loginState,
            Integer riskStatus,
            Integer muteStatus,
            long now) {
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status,
                     mute_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, accountId, accountState, loginState, riskStatus, muteStatus, now, now);
    }

    private long seedAccountGroup(long tenantId, String name, long now) {
        return insertAndReturnId("""
                INSERT INTO account_group
                    (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private void seedOccupancy(long tenantId, long accountId, long now) {
        jdbc.update("""
                INSERT INTO marketing_account_occupancy
                    (tenant_id, account_id, marketing_task_id, occupied_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, accountId, Long.MAX_VALUE, now, now, now);
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(statement);
            return statement;
        }, keys);
        Number key = keys.getKey();
        assertThat(key).as("generated key").isNotNull();
        return key.longValue();
    }

    private static String phone(long now, String suffix) {
        return now + suffix;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws java.sql.SQLException;
    }
}
