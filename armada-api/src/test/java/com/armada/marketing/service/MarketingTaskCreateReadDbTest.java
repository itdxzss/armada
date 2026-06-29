package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
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

/**
 * 营销任务第一阶段:保存任务、生成账号×群组目标、列表和详情读取。
 *
 * <p>本测试只验证后台数据流,不触发真实发送引擎。</p>
 */
class MarketingTaskCreateReadDbTest extends DbTestBase {

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_SENDING = 2;

    @Autowired
    private MarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createTask_persistsTaskAndTargetsFromSelections() {
        Fixture fixture = seedFixture("create");
        CreateMarketingTaskDTO request = request(
                "巴铁烟草群发",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));

        MarketingTaskVO created = service.createTask(request);

        assertThat(created.id()).isNotNull();
        assertThat(created.taskName()).isEqualTo("巴铁烟草群发");
        assertThat(created.status()).isEqualTo(STATUS_PENDING);
        assertThat(created.selectedAccountCount()).isEqualTo(1);
        assertThat(created.targetGroupCount()).isEqualTo(1);
        assertThat(created.targetPairCount()).isEqualTo(1);

        Integer targetRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_task_target WHERE marketing_task_id = ?",
                Integer.class,
                created.id());
        assertThat(targetRows).isEqualTo(1);
    }

    @Test
    void createTask_immediateStartOnlyChangesStatusWithoutSending() {
        Fixture fixture = seedFixture("immediate");
        MarketingTaskVO created = service.createTask(request(
                "立即启动任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "IMMEDIATE",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        assertThat(created.status()).isEqualTo(STATUS_SENDING);
        assertThat(created.sentMessageCount()).isZero();
        assertThat(created.lastSentAt()).isNull();
    }

    @Test
    void listTasks_filtersByKeywordAndStatus() {
        Fixture one = seedFixture("list-one");
        Fixture two = seedFixture("list-two");
        service.createTask(request("目标任务A", one.accountGroupId(), one.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(one.accountId(), List.of(one.groupLinkId())))));
        service.createTask(request("其他任务B", two.accountGroupId(), two.templateId(), "IMMEDIATE",
                List.of(new MarketingSelectionDTO(two.accountId(), List.of(two.groupLinkId())))));

        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setKeyword("目标");
        query.setStatus(STATUS_PENDING);
        query.setPageSize(10);

        PageResult<MarketingTaskVO> page = service.listTasks(query);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list()).singleElement()
                .extracting(MarketingTaskVO::taskName)
                .isEqualTo("目标任务A");
    }

    @Test
    void getDetail_returnsTargetRows() {
        Fixture fixture = seedFixture("detail");
        MarketingTaskVO created = service.createTask(request("详情任务", fixture.accountGroupId(), fixture.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.id()).isEqualTo(created.id());
        assertThat(detail.targets()).hasSize(1);
        assertThat(detail.targets().get(0).accountPhone()).isEqualTo(fixture.phone());
        assertThat(detail.targets().get(0).groupJid()).isEqualTo(fixture.groupJid());
        assertThat(detail.targets().get(0).groupLinkUrl()).isEqualTo(fixture.groupUrl());
    }

    @Test
    void createTask_rejectsSelectionWithoutActiveMembership() {
        Fixture fixture = seedFixture("missing-membership");
        jdbc.update("UPDATE account_group_membership SET deleted_at = ? WHERE account_id = ? AND group_link_id = ?",
                System.currentTimeMillis(), fixture.accountId(), fixture.groupLinkId());

        CreateMarketingTaskDTO req = request("无在群关系任务", fixture.accountGroupId(), fixture.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可用");
    }

    private CreateMarketingTaskDTO request(String taskName,
                                           long accountGroupId,
                                           long templateId,
                                           String startMode,
                                           List<MarketingSelectionDTO> selections) {
        return new CreateMarketingTaskDTO(
                taskName,
                accountGroupId,
                "营销账号组",
                templateId,
                "营销模板",
                startMode,
                1,
                30,
                true,
                true,
                false,
                "备注",
                selections);
    }

    private Fixture seedFixture(String suffix) {
        long now = System.currentTimeMillis();
        long accountGroupId = insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "营销账号组-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
        long templateId = insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, content, body_text, buttons, created_at, updated_at)
                VALUES (?, ?, 1, 'PROMO', '内容', '正文', NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "营销模板-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
        String phone = "923000" + Math.abs(suffix.hashCode() % 1000000);
        long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        String groupUrl = "https://chat.whatsapp.com/" + suffix;
        long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 2, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, groupUrl);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        String groupJid = "1203630" + Math.abs(suffix.hashCode()) + "@g.us";
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, announce_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "WA群-" + suffix, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, now, now);
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now);
        return new Fixture(accountGroupId, templateId, accountId, phone, groupLinkId, groupUrl, groupJid);
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

    private record Fixture(
            long accountGroupId,
            long templateId,
            long accountId,
            String phone,
            long groupLinkId,
            String groupUrl,
            String groupJid) {
    }
}
