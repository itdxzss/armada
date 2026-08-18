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

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_SENDING = 2;
    private static final int STATUS_PAUSED = 5;
    private static final int STATUS_CLOSED = 8;

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
        assertThat(created.marketingTemplateContent()).isEqualTo("内容");
        assertThat(created.marketingTemplateBodyText()).isEqualTo("正文");
        assertThat(created.marketingTemplatePromotionLink()).isNull();
        assertThat(started.marketingTemplateContent()).isEqualTo("内容");
        assertThat(started.marketingTemplateBodyText()).isEqualTo("正文");
        assertThat(started.marketingTemplatePromotionLink()).isNull();
    }

    @Test
    void startTask_futurePendingTask_staysWaitingWithoutStartedAt() {
        Fixture fixture = seedFixture("start-future-pending");
        long now = System.currentTimeMillis();
        MarketingTaskVO created = createTaskWithTimes(
                "未来等待任务", fixture, "PENDING", now + 60_000L, now + 600_000L);

        MarketingTaskVO activated = service.startTask(created.id());

        assertThat(activated.status()).isEqualTo(STATUS_PENDING);
        assertThat(activated.startedAt()).isNull();
        assertThat(nextRoundAt(created.id())).isNull();
        assertThat(occupancyCount(created.id())).isEqualTo(1);
    }

    @Test
    void resumeTask_pausedBeforeOriginalStart_isRejected() {
        Fixture fixture = seedFixture("resume-future-stopped");
        long now = System.currentTimeMillis();
        MarketingTaskVO created = createTaskWithTimes(
                "未来已停止任务", fixture, "PENDING", now + 60_000L, now + 600_000L);
        jdbc.update("UPDATE marketing_task SET status = 5 WHERE id = ?", created.id());

        assertThatThrownBy(() -> service.resumeTask(created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未到任务计划开始时间");
        assertThat(nextRoundAt(created.id())).isNull();
        assertThat(occupancyCount(created.id())).isEqualTo(1);
    }

    @Test
    void pauseAndResumeTask_insideWindow_retainsAccountLock() {
        Fixture fixture = seedFixture("resume-active-stopped");
        long now = System.currentTimeMillis();
        MarketingTaskVO created = createTaskWithTimes(
                "窗口内已停止任务", fixture, "IMMEDIATE", now - 60_000L, now + 600_000L);
        MarketingTaskVO paused = service.pauseTask(created.id());

        MarketingTaskVO resumed = service.resumeTask(paused.id());

        assertThat(paused.status()).isEqualTo(STATUS_PAUSED);
        assertThat(resumed.status()).isEqualTo(STATUS_SENDING);
        assertThat(nextRoundAt(created.id())).isNotNull();
        assertThat(occupancyCount(created.id())).isEqualTo(1);
    }

    @Test
    void resumeTask_pausedTaskAfterEnd_isRejected() {
        Fixture fixture = seedFixture("resume-expired-stopped");
        long now = System.currentTimeMillis();
        MarketingTaskVO created = createTaskWithTimes(
                "已过期停止任务", fixture, "IMMEDIATE", now - 120_000L, now + 60_000L);
        service.pauseTask(created.id());
        jdbc.update("UPDATE marketing_task SET task_end_at = ? WHERE id = ?", now - 1L, created.id());

        assertThatThrownBy(() -> service.resumeTask(created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("计划已结束")
                .hasMessageContaining("不可继续");
    }

    @Test
    void pauseTask_sendingTask_setsPausedWithoutReleasingAccount() {
        Fixture fixture = seedFixture("stop-sending");
        MarketingTaskVO created = createTask("发送中任务", fixture, "IMMEDIATE");
        assertThat(occupancyCount(created.id())).isEqualTo(1);

        MarketingTaskVO paused = service.pauseTask(created.id());

        assertThat(paused.status()).isEqualTo(STATUS_PAUSED);
        assertThat(paused.startedAt()).isEqualTo(created.startedAt());
        assertThat(occupancyCount(created.id())).isEqualTo(1);
    }

    @Test
    void createTask_accountGroupContainsOccupiedAccount_isRejected() {
        Fixture fixture = seedFixture("create-occupied-group");
        MarketingTaskVO running = createTask("占用分组任务", fixture, "IMMEDIATE");

        assertThatThrownBy(() -> createTask("同分组新任务", fixture, "PENDING"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该账号正在被任务【占用分组任务】占用，请先关闭原任务后再使用。");
        assertThat(occupancyCount(running.id())).isEqualTo(1);
    }

    @Test
    void closeTask_isIrreversibleAndReleasesAccounts() {
        MarketingTaskVO created = createTask("手动关闭任务", seedFixture("close-task"), "IMMEDIATE");

        MarketingTaskVO closed = service.closeTask(created.id());

        assertThat(closed.status()).isEqualTo(STATUS_CLOSED);
        assertThat(closed.finishedAt()).isNotNull();
        assertThat(occupancyCount(created.id())).isZero();
        assertThatThrownBy(() -> service.startTask(created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("只有未启动的任务可以启动");
        assertThatThrownBy(() -> service.closeTask(created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已完成或已关闭的任务不可手动关闭");
    }

    @Test
    void batchDelete_rejectsNonTerminalTasksAndLeavesAllRows() {
        MarketingTaskVO pending = createTask("未启动不可删任务", seedFixture("delete-pending"), "PENDING");
        MarketingTaskVO sending = createTask("发送中不可删任务", seedFixture("delete-sending"), "IMMEDIATE");

        assertThatThrownBy(() -> service.batchDelete(List.of(pending.id(), sending.id())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未结束");

        assertThat(deletedAt(pending.id())).isNull();
        assertThat(deletedAt(sending.id())).isNull();
    }

    @Test
    void batchDelete_terminalTasks_softDeletesIdempotently() {
        MarketingTaskVO pending = createTask("待关闭任务", seedFixture("delete-ok-pending"), "PENDING");
        MarketingTaskVO sending = createTask("待关闭发送任务", seedFixture("delete-ok-sending"), "IMMEDIATE");
        MarketingTaskVO firstClosed = service.closeTask(pending.id());
        MarketingTaskVO secondClosed = service.closeTask(sending.id());

        int deleted = service.batchDelete(List.of(firstClosed.id(), secondClosed.id()));
        int deletedAgain = service.batchDelete(List.of(firstClosed.id(), secondClosed.id()));

        assertThat(deleted).isEqualTo(2);
        assertThat(deletedAgain).isZero();
        assertThat(deletedAt(pending.id())).isNotNull();
        assertThat(deletedAt(secondClosed.id())).isNotNull();
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
                null,
                null,
                null,
                1,
                null,
                30,
                true,
                true,
                false,
                false,
                30,
                "MINUTE",
                "状态变更测试",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    }

    private MarketingTaskVO createTaskWithTimes(String taskName,
                                                Fixture fixture,
                                                String startMode,
                                                Long taskStartAt,
                                                Long taskEndAt) {
        return service.createTask(new CreateMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                "营销账号组",
                fixture.templateId(),
                "营销模板",
                startMode,
                null,
                taskStartAt,
                taskEndAt,
                1,
                null,
                30,
                true,
                true,
                false,
                false,
                30,
                "MINUTE",
                "状态变更测试",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
    }

    private Long nextRoundAt(Long taskId) {
        return jdbc.queryForObject(
                "SELECT next_round_at FROM marketing_task WHERE id = ?", Long.class, taskId);
    }

    private Long deletedAt(Long taskId) {
        return jdbc.queryForObject("SELECT deleted_at FROM marketing_task WHERE id = ?", Long.class, taskId);
    }

    private int occupancyCount(Long taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_account_occupancy WHERE marketing_task_id = ?",
                Integer.class,
                taskId);
        return count == null ? 0 : count;
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
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now, now);
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
