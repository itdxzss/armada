package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.vo.MarketingTaskVO;
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

/**
 * 营销任务状态变更与批量软删规则。
 *
 * <p>本测试只覆盖任务主表状态流转和删除守卫,不启动发送引擎。</p>
 */
class MarketingTaskMutationDbTest extends DbTestBase {

    private static final int STATUS_SENDING = 2;
    private static final int STATUS_STOPPED = 5;

    @Autowired
    private MarketingTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startTask_pendingTask_setsSendingAndStartedAt() {
        Fixture fixture = seedFixture("start-pending");
        MarketingTaskVO created = createTask("待启动任务", fixture, "PENDING");

        MarketingTaskVO started = service.startTask(created.id());

        assertThat(started.status()).isEqualTo(STATUS_SENDING);
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.updatedAt()).isNotNull();
    }

    @Test
    void stopTask_sendingTask_setsStopped() {
        Fixture fixture = seedFixture("stop-sending");
        MarketingTaskVO created = createTask("发送中任务", fixture, "IMMEDIATE");

        MarketingTaskVO stopped = service.stopTask(created.id());

        assertThat(stopped.status()).isEqualTo(STATUS_STOPPED);
        assertThat(stopped.startedAt()).isEqualTo(created.startedAt());
    }

    @Test
    void startTask_stoppedTask_restartsTask() {
        Fixture fixture = seedFixture("restart-stopped");
        MarketingTaskVO created = createTask("重启任务", fixture, "IMMEDIATE");
        MarketingTaskVO stopped = service.stopTask(created.id());

        MarketingTaskVO restarted = service.startTask(stopped.id());

        assertThat(restarted.status()).isEqualTo(STATUS_SENDING);
        assertThat(restarted.startedAt()).isEqualTo(created.startedAt());
    }

    @Test
    void batchDelete_rejectsSendingTaskAndLeavesAllRows() {
        MarketingTaskVO pending = createTask("可删任务", seedFixture("delete-pending"), "PENDING");
        MarketingTaskVO sending = createTask("发送中不可删任务", seedFixture("delete-sending"), "IMMEDIATE");

        assertThatThrownBy(() -> service.batchDelete(List.of(pending.id(), sending.id())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发送中");

        assertThat(deletedAt(pending.id())).isNull();
        assertThat(deletedAt(sending.id())).isNull();
    }

    @Test
    void batchDelete_nonSendingTasks_softDeletesIdempotently() {
        MarketingTaskVO pending = createTask("待删待启动任务", seedFixture("delete-ok-pending"), "PENDING");
        MarketingTaskVO sending = createTask("待删已停止任务", seedFixture("delete-ok-stopped"), "IMMEDIATE");
        MarketingTaskVO stopped = service.stopTask(sending.id());

        int deleted = service.batchDelete(List.of(pending.id(), stopped.id()));
        int deletedAgain = service.batchDelete(List.of(pending.id(), stopped.id()));

        assertThat(deleted).isEqualTo(2);
        assertThat(deletedAgain).isZero();
        assertThat(deletedAt(pending.id())).isNotNull();
        assertThat(deletedAt(stopped.id())).isNotNull();
    }

    @Test
    void batchDelete_emptyInput_returnsZero() {
        assertThat(service.batchDelete(null)).isZero();
        assertThat(service.batchDelete(List.of())).isZero();
    }

    private MarketingTaskVO createTask(String taskName, Fixture fixture, String startMode) {
        return service.createTask(new CreateMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                "营销账号组",
                fixture.templateId(),
                "营销模板",
                startMode,
                1,
                30,
                true,
                true,
                false,
                "状态变更测试",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    }

    private Long deletedAt(Long taskId) {
        return jdbc.queryForObject("SELECT deleted_at FROM marketing_task WHERE id = ?", Long.class, taskId);
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
        String phone = "923200" + Math.abs(suffix.hashCode() % 1000000);
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
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now);
        return new Fixture(accountGroupId, templateId, accountId, groupLinkId);
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
            long groupLinkId) {
    }
}
