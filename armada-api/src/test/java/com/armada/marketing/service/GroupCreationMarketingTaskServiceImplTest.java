package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingMaterialDTO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.shared.exception.BusinessException;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupCreationMarketingTaskServiceImplTest extends DbTestBase {

    @Autowired
    private GroupCreationMarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createPairsAccountsByIdAscendingWithUploadedFileOrder() {
        Fixture fixture = seedFixture("ordered", 3);

        GroupCreationMarketingTaskDetailVO created = service.createTask(request(
                "建群营销-顺序",
                fixture,
                List.of(material("b.txt", "8613911111111"), material("a.txt", "8613900000000"))));

        assertThat(created.matchedItemCount()).isEqualTo(2);
        assertThat(created.unmatchedFileCount()).isZero();
        assertThat(created.items()).hasSize(2);
        assertThat(created.items().get(0).accountId()).isEqualTo(fixture.accountIds().get(0));
        assertThat(created.items().get(0).fileName()).isEqualTo("b.txt");
        assertThat(created.items().get(1).accountId()).isEqualTo(fixture.accountIds().get(1));
        assertThat(created.items().get(1).fileName()).isEqualTo("a.txt");
    }

    @Test
    void createStoresSendIntervalAndFiltersInvalidAndDuplicatePhones() {
        Fixture fixture = seedFixture("interval-filter", 1);

        GroupCreationMarketingTaskDetailVO created = service.createTask(request(
                "建群营销-间隔",
                fixture,
                45,
                List.of(material("a.txt", """
                        8613900000000
                        8613900000000
                        bad-phone
                        8613911111111,备注
                        """))));

        assertThat(created.sendIntervalSeconds()).isEqualTo(45);
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.participantCount()).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject(
                "SELECT send_interval_seconds FROM group_creation_marketing_task WHERE id = ?",
                Integer.class,
                created.id())).isEqualTo(45);
    }

    @Test
    void createIgnoresExtraAccountsAndReturnsMatchedCount() {
        Fixture fixture = seedFixture("extra-account", 3);

        GroupCreationMarketingTaskDetailVO created = service.createTask(request(
                "建群营销-账号多",
                fixture,
                List.of(material("a.txt", "8613900000000"), material("b.txt", "8613911111111"))));

        assertThat(created.matchedItemCount()).isEqualTo(2);
        assertThat(created.unmatchedFileCount()).isZero();
        assertThat(created.items()).extracting("accountId")
                .containsExactly(fixture.accountIds().get(0), fixture.accountIds().get(1));
    }

    @Test
    void createIgnoresExtraFilesAndReportsUnmatchedFileCount() {
        Fixture fixture = seedFixture("extra-file", 1);

        GroupCreationMarketingTaskDetailVO created = service.createTask(request(
                "建群营销-文件多",
                fixture,
                List.of(
                        material("a.txt", "8613900000000"),
                        material("b.txt", "8613911111111"),
                        material("c.txt", "8613922222222"))));

        assertThat(created.matchedItemCount()).isEqualTo(1);
        assertThat(created.unmatchedFileCount()).isEqualTo(2);
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.accountId()).isEqualTo(fixture.accountIds().get(0));
            assertThat(item.fileName()).isEqualTo("a.txt");
        });
    }

    @Test
    void createRejectsWhenNoMatchedItemsExist() {
        Fixture fixture = seedFixture("no-match", 0);

        assertThatThrownBy(() -> service.createTask(request(
                "建群营销-无匹配",
                fixture,
                List.of(material("a.txt", "8613900000000")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可执行");
    }

    @Test
    void createMatchesOnlyNormalOnlineUsableAccounts() {
        long now = System.currentTimeMillis();
        Long groupId = insertAccountGroup("GCM-usable-only-" + now, now);
        Long templateId = insertTemplate("GCM模板-usable-only-" + now, now);
        insertAccountWithState(groupId, "8613000001001", "missing-protocol", "", AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, 1, null, now);
        insertAccountWithState(groupId, "8613000001002", "offline", "acc_offline", AccountStateCode.NORMAL,
                AccountLoginStateCode.OFFLINE, 1, null, now);
        insertAccountWithState(groupId, "8613000001003", "banned", "acc_banned", AccountStateCode.BANNED,
                AccountLoginStateCode.ONLINE, 1, null, now);
        insertAccountWithState(groupId, "8613000001004", "risk", "acc_risk", AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, 2, null, now);
        insertAccountWithState(groupId, "8613000001005", "muted", "acc_muted", AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, 1, 1, now);
        Long usableId = insertAccountWithState(groupId, "8613000001006", "usable", "acc_usable", AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE, 1, null, now);
        Fixture fixture = new Fixture(groupId, "GCM-usable-only-" + now, templateId,
                "GCM模板-usable-only-" + now, List.of(usableId));

        GroupCreationMarketingTaskDetailVO created = service.createTask(request(
                "建群营销-只用可用账号",
                fixture,
                List.of(material("a.txt", "8613900000000"), material("b.txt", "8613911111111"))));

        assertThat(created.matchedItemCount()).isEqualTo(1);
        assertThat(created.unmatchedFileCount()).isEqualTo(1);
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.accountId()).isEqualTo(usableId);
            assertThat(item.fileName()).isEqualTo("a.txt");
            assertThat(item.protocolAccountId()).isEqualTo("acc_usable");
        });
        assertThat(service.accountCandidates(groupId)).extracting("accountId").containsExactly(usableId);
    }

    private CreateGroupCreationMarketingTaskDTO request(String taskName,
                                                        Fixture fixture,
                                                        List<GroupCreationMarketingMaterialDTO> materials) {
        return request(taskName, fixture, 30, materials);
    }

    private CreateGroupCreationMarketingTaskDTO request(String taskName,
                                                        Fixture fixture,
                                                        int sendIntervalSeconds,
                                                        List<GroupCreationMarketingMaterialDTO> materials) {
        return new CreateGroupCreationMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                fixture.accountGroupName(),
                fixture.templateId(),
                fixture.templateName(),
                sendIntervalSeconds,
                "活动群",
                "remark",
                materials);
    }

    private GroupCreationMarketingMaterialDTO material(String fileName, String content) {
        return new GroupCreationMarketingMaterialDTO(fileName, content);
    }

    private Fixture seedFixture(String suffix, int accountCount) {
        long now = System.currentTimeMillis();
        Long groupId = insertAccountGroup("GCM-" + suffix + "-" + now, now);
        Long templateId = insertTemplate("GCM模板-" + suffix + "-" + now, now);
        List<Long> accountIds = new java.util.ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            Long accountId = insertAccount(groupId, "8613000" + now % 100000 + i, "acc_" + suffix + "_" + i, now);
            insertAccountState(accountId, now);
            accountIds.add(accountId);
        }
        return new Fixture(groupId, "GCM-" + suffix + "-" + now, templateId, "GCM模板-" + suffix + "-" + now, accountIds);
    }

    private Long insertAccountGroup(String name, long now) {
        return insertReturningId("""
                INSERT INTO account_group (tenant_id, name, remark, system_builtin, created_at, updated_at)
                VALUES (?, ?, NULL, 0, ?, ?)
                """, TEST_TENANT_ID, name, now, now);
    }

    private Long insertTemplate(String name, long now) {
        return insertReturningId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, content, body_text, created_at, updated_at)
                VALUES (?, ?, 1, 'text', 'content', 'body', ?, ?)
                """, TEST_TENANT_ID, name, now, now);
    }

    private Long insertAccount(Long groupId, String phone, String protocolAccountId, long now) {
        return insertReturningId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id, protocol_account_id, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, ?, ?, ?)
                """, TEST_TENANT_ID, phone, groupId, protocolAccountId, now, now);
    }

    private void insertAccountState(Long accountId, long now) {
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, NULL, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
    }

    private Long insertAccountWithState(Long groupId,
                                        String phone,
                                        String suffix,
                                        String protocolAccountId,
                                        Integer accountState,
                                        Integer loginState,
                                        Integer riskStatus,
                                        Integer muteStatus,
                                        long now) {
        Long accountId = insertAccount(groupId, phone, protocolAccountId, now);
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, mute_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, accountState, loginState, riskStatus, muteStatus, now, now);
        return accountId;
    }

    private Long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private record Fixture(Long accountGroupId,
                           String accountGroupName,
                           Long templateId,
                           String templateName,
                           List<Long> accountIds) {
    }
}
