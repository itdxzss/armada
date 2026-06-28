package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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
 * 营销任务侧修改营销素材:任务只定位模板,实际更新委托营销模板服务。
 */
class MarketingTaskMaterialUpdateDbTest extends DbTestBase {

    @Autowired
    private MarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void updateMarketingTemplate_updatesReferencedSharedTemplate() {
        Fixture fixture = seedFixture("material-update");
        MarketingTaskVO task = createTask("修改素材任务", fixture);
        MarketingTemplateDTO request = new MarketingTemplateDTO(
                "营销模板-" + fixture.suffix(),
                1,
                "PROMO",
                null,
                "更新后的内容",
                "更新后的正文",
                null,
                "https://example.com/new",
                "从任务侧更新素材");

        MarketingTemplateVO updated = service.updateMarketingTemplate(task.id(), request);

        assertThat(updated.id()).isEqualTo(fixture.templateId());
        assertThat(updated.content()).isEqualTo("更新后的内容");
        assertThat(updated.bodyText()).isEqualTo("更新后的正文");
        assertThat(updated.promotionLink()).isEqualTo("https://example.com/new");
        assertThat(jdbc.queryForObject(
                "SELECT body_text FROM marketing_template WHERE id = ?",
                String.class,
                fixture.templateId())).isEqualTo("更新后的正文");
    }

    @Test
    void updateMarketingTemplate_missingTask_throwsNotFound() {
        MarketingTemplateDTO request = new MarketingTemplateDTO(
                "不存在任务模板",
                1,
                "PROMO",
                null,
                "内容",
                "正文",
                null,
                null,
                "备注");

        assertThatThrownBy(() -> service.updateMarketingTemplate(99999999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.code()));
    }

    private MarketingTaskVO createTask(String taskName, Fixture fixture) {
        return service.createTask(new CreateMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                "营销账号组",
                fixture.templateId(),
                "营销模板",
                "PENDING",
                1,
                30,
                true,
                true,
                false,
                "素材更新测试",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
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
        String phone = "923400" + Math.abs(suffix.hashCode() % 1000000);
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
        return new Fixture(suffix, accountGroupId, templateId, accountId, groupLinkId);
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
            String suffix,
            long accountGroupId,
            long templateId,
            long accountId,
            long groupLinkId) {
    }
}
