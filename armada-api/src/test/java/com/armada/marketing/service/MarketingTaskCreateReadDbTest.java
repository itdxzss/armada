package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import com.armada.testsupport.DbTestBase;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private static final int TARGET_STATUS_PARTIAL_FAILED = 5;
    private static final long THREE_DAYS_MS = 72L * 60L * 60L * 1000L;
    private static final long OTHER_TENANT_ID = 2L;

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
        assertThat(created.targetGroupCount())
                .as("累计成功群数在首次成功回调前必须为0")
                .isZero();
        assertThat(created.targetPairCount()).isEqualTo(1);
        assertThat(created.accountGroupSendIntervalSeconds()).isEqualByComparingTo("0.5");

        Integer targetRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketing_task_target WHERE marketing_task_id = ?",
                Integer.class,
                created.id());
        assertThat(targetRows).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT account_group_send_interval_ms FROM marketing_task WHERE id = ?",
                Integer.class,
                created.id())).isEqualTo(500);
        assertThat(service.getDetail(created.id()).accountGroupSendIntervalSeconds())
                .isEqualByComparingTo("0.5");

        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setKeyword("巴铁烟草群发");
        assertThat(service.listTasks(query).list()).singleElement()
                .extracting(MarketingTaskVO::accountGroupSendIntervalSeconds)
                .isEqualTo(new BigDecimal("0.5"));
    }

    @Test
    void createTask_persistsExplicitAccountGroupSendInterval() {
        Fixture fixture = seedFixture("explicit-account-group-interval");
        MarketingTaskVO created = service.createTask(requestWithInterval(
                "显式账号群间隔任务",
                fixture,
                new BigDecimal("2.3"),
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        assertThat(created.accountGroupSendIntervalSeconds()).isEqualByComparingTo("2.3");
        assertThat(jdbc.queryForObject(
                "SELECT account_group_send_interval_ms FROM marketing_task WHERE id = ?",
                Integer.class,
                created.id())).isEqualTo(2_300);
        assertThat(service.getDetail(created.id()).accountGroupSendIntervalSeconds())
                .isEqualByComparingTo("2.3");
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
    void createTask_defaultsAccountGroupSendAtFromTaskStartMinusSeventyTwoHours() {
        Fixture fixture = seedFixture("default-group-send-time");
        long taskStartAt = System.currentTimeMillis() + 120_000L;
        long taskEndAt = taskStartAt + 600_000L;

        MarketingTaskVO created = service.createTask(requestWithTimes(
                "默认群组发送时间任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                null,
                taskStartAt,
                taskEndAt,
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        assertThat(created.accountGroupSendAt()).isEqualTo(taskStartAt - THREE_DAYS_MS);
        assertThat(created.taskStartAt()).isEqualTo(taskStartAt);
        assertThat(created.taskEndAt()).isEqualTo(taskEndAt);
    }

    @Test
    void createTask_rejectsManualAccountGroupSendAtOlderThanSeventyTwoHours() {
        Fixture fixture = seedFixture("old-group-send-time");
        long now = System.currentTimeMillis();

        CreateMarketingTaskDTO req = requestWithTimes(
                "过早群组发送时间任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                now - THREE_DAYS_MS - 1_000L,
                now + 120_000L,
                now + 600_000L,
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号群组发送时间最多支持追溯72小时");
    }

    @Test
    void createTask_accountDynamicSelectionPersistsAccountTargetWithoutGroupSnapshot() {
        Fixture fixture = seedFixture("account-dynamic", false);
        MarketingTaskVO created = service.createTask(request(
                "账号动态任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), "ACCOUNT_DYNAMIC", List.of()))));

        assertThat(created.selectedAccountCount()).isEqualTo(1);
        assertThat(created.targetGroupCount()).isZero();
        assertThat(created.targetPairCount()).isEqualTo(1);

        MarketingTaskDetailVO detail = service.getDetail(created.id());
        assertThat(detail.targets()).singleElement().satisfies(target -> {
            assertThat(target.targetScope()).isEqualTo("ACCOUNT_DYNAMIC");
            assertThat(target.groupLinkId()).isNull();
            assertThat(target.groupJid()).isNull();
            assertThat(target.groupLinkUrl()).isNull();
        });
        Integer targetScope = jdbc.queryForObject(
                "SELECT target_scope FROM marketing_task_target WHERE marketing_task_id = ?",
                Integer.class,
                created.id());
        assertThat(targetScope).isEqualTo(2);
    }

    @Test
    void createTask_accountDynamicAllowsOnlineTakeoverLifecycleStates() {
        for (int accountState : List.of(
                AccountStateCode.LOGIN_REPLACED,
                AccountStateCode.TAKING_OVER)) {
            Fixture fixture = seedTakeoverFixture(
                    "takeover-dynamic-" + accountState,
                    accountState,
                    AccountLoginStateCode.ONLINE);

            MarketingTaskVO created = service.createTask(request(
                    "抢登动态任务-" + accountState,
                    fixture.accountGroupId(),
                    fixture.templateId(),
                    "PENDING",
                    List.of(new MarketingSelectionDTO(
                            fixture.accountId(),
                            "ACCOUNT_DYNAMIC",
                            List.of()))));

            assertThat(created.selectedAccountCount()).isEqualTo(1);
            assertThat(created.targetPairCount()).isEqualTo(1);
        }
    }

    @Test
    void createTask_fixedGroupAllowsOnlineTakeoverLifecycleStates() {
        for (int accountState : List.of(
                AccountStateCode.LOGIN_REPLACED,
                AccountStateCode.TAKING_OVER)) {
            Fixture fixture = seedTakeoverFixture(
                    "takeover-fixed-" + accountState,
                    accountState,
                    AccountLoginStateCode.ONLINE);

            MarketingTaskVO created = service.createTask(request(
                    "抢登固定群任务-" + accountState,
                    fixture.accountGroupId(),
                    fixture.templateId(),
                    "PENDING",
                    List.of(new MarketingSelectionDTO(
                            fixture.accountId(),
                            List.of(fixture.groupLinkId())))));

            assertThat(created.selectedAccountCount()).isEqualTo(1);
            assertThat(created.targetPairCount()).isEqualTo(1);
        }
    }

    @Test
    void createTask_fixedGroupAllowsRetainedExitedMembership() {
        Fixture fixture = seedTakeoverFixture(
                "fixed-kicked-membership",
                AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE account_group_membership
                SET membership_status = 3,
                    status_source = 'TEST_KICKED',
                    status_updated_at = ?,
                    updated_at = ?
                WHERE account_id = ? AND group_link_id = ? AND deleted_at IS NULL
                """, now, now, fixture.accountId(), fixture.groupLinkId());

        MarketingTaskVO created = service.createTask(request(
                "被踢群仍可选任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(), List.of(fixture.groupLinkId())))));
        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(created.targetPairCount()).isEqualTo(1);
        assertThat(detail.accountTargets()).singleElement()
                .satisfies(account -> assertThat(account.groups()).singleElement()
                        .satisfies(group -> assertThat(group.membershipStatus()).isEqualTo("KICKED_OUT")));
    }

    @Test
    void createTask_rejectsOfflineTakingOverAccount() {
        Fixture fixture = seedTakeoverFixture(
                "takeover-offline",
                AccountStateCode.TAKING_OVER,
                AccountLoginStateCode.OFFLINE);
        CreateMarketingTaskDTO req = request(
                "离线抢登中任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        "ACCOUNT_DYNAMIC",
                        List.of())));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号不可用");
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
    void listTasks_returnsCurrentTemplateInfoForSharedAndDifferentTemplates() {
        Fixture first = seedFixture("template-info-first");
        Fixture second = seedFixture("template-info-second");
        Fixture third = seedFixture("template-info-third");
        jdbc.update("""
                UPDATE marketing_template
                SET content = ?, body_text = ?, promotion_link = ?
                WHERE id = ?
                """, "共享标题", "共享正文", "https://example.com/shared", first.templateId());
        jdbc.update("""
                UPDATE marketing_template
                SET content = ?, body_text = ?, promotion_link = NULL
                WHERE id = ?
                """, "独立标题", "独立正文", third.templateId());

        service.createTask(request("模板展示任务A", first.accountGroupId(), first.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(first.accountId(), List.of(first.groupLinkId())))));
        service.createTask(request("模板展示任务B", second.accountGroupId(), first.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(second.accountId(), List.of(second.groupLinkId())))));
        service.createTask(request("模板展示任务C", third.accountGroupId(), third.templateId(), "PENDING",
                List.of(new MarketingSelectionDTO(third.accountId(), List.of(third.groupLinkId())))));
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setKeyword("模板展示任务");
        query.setPageSize(10);

        PageResult<MarketingTaskVO> page = service.listTasks(query);
        Map<String, MarketingTaskVO> byName = page.list().stream()
                .collect(Collectors.toMap(MarketingTaskVO::taskName, Function.identity()));

        assertThat(page.total()).isEqualTo(3);
        assertThat(List.of(byName.get("模板展示任务A"), byName.get("模板展示任务B")))
                .allSatisfy(row -> {
                    assertThat(row.marketingTemplateContent()).isEqualTo("共享标题");
                    assertThat(row.marketingTemplateBodyText()).isEqualTo("共享正文");
                    assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/shared");
                });
        assertThat(byName.get("模板展示任务C").marketingTemplateContent()).isEqualTo("独立标题");
        assertThat(byName.get("模板展示任务C").marketingTemplateBodyText()).isEqualTo("独立正文");
        assertThat(byName.get("模板展示任务C").marketingTemplatePromotionLink()).isNull();
    }

    @Test
    void listTasks_returnsFirstLinkJumpButtonAsPromotionLink() {
        Fixture fixture = seedFixture("template-button-link");
        jdbc.update("""
                UPDATE marketing_template
                SET link_mode = ?, buttons = ?, promotion_link = NULL
                WHERE id = ?
                """,
                LinkMode.BUTTON.code(),
                """
                [{"type":"QUICK_REPLY","text":"咨询","param":null},
                 {"type":"LINK_JUMP","text":"首个链接","param":"https://example.com/first"},
                 {"type":"LINK_JUMP","text":"第二链接","param":"https://example.com/second"}]
                """,
                fixture.templateId());
        MarketingTaskVO created = service.createTask(request(
                "按钮推广链接任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setId(created.id());
        query.setPageSize(10);

        MarketingTaskVO row = service.listTasks(query).list().get(0);

        assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/first");
    }

    @Test
    void listTasks_keepsTaskWhenReferencedTemplateWasSoftDeleted() {
        Fixture fixture = seedFixture("template-info-deleted");
        MarketingTaskVO created = service.createTask(request(
                "模板已删除仍展示任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
        jdbc.update("UPDATE marketing_template SET deleted_at = ? WHERE id = ?",
                System.currentTimeMillis(), fixture.templateId());
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setId(created.id());
        query.setPageSize(10);

        PageResult<MarketingTaskVO> page = service.listTasks(query);

        assertThat(page.list()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(created.id());
            assertThat(row.marketingTemplateContent()).isNull();
            assertThat(row.marketingTemplateBodyText()).isNull();
            assertThat(row.marketingTemplatePromotionLink()).isNull();
        });
    }

    @Test
    void listTasks_doesNotExposeTemplateFieldsFromAnotherTenant() {
        Fixture fixture = seedFixture("template-info-tenant");
        MarketingTaskVO created = service.createTask(request(
                "跨租户模板不可见任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));
        long now = System.currentTimeMillis();
        long foreignTemplateId = insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, content, body_text,
                     promotion_link, created_at, updated_at)
                VALUES (?, ?, 1, 'PROMO', ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, OTHER_TENANT_ID);
            ps.setString(2, "其他租户模板");
            ps.setString(3, "其他租户标题");
            ps.setString(4, "其他租户正文");
            ps.setString(5, "https://other-tenant.example.com");
            ps.setLong(6, now);
            ps.setLong(7, now);
        });
        jdbc.update("UPDATE marketing_task SET marketing_template_id = ? WHERE id = ?",
                foreignTemplateId, created.id());
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setId(created.id());
        query.setPageSize(10);

        PageResult<MarketingTaskVO> page = service.listTasks(query);

        assertThat(page.list()).singleElement().satisfies(row -> {
            assertThat(row.marketingTemplateContent()).isNull();
            assertThat(row.marketingTemplateBodyText()).isNull();
            assertThat(row.marketingTemplatePromotionLink()).isNull();
        });
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
    void getDetail_rollsUpAccountGroupsFromSendAttempts() {
        Fixture fixture = seedFixture("detail-rollup");
        GroupFixture secondGroup = seedGroup("detail-rollup-second",
                "120363099@g.us",
                "https://chat.whatsapp.com/detail-rollup-second");
        seedMembership(fixture.accountId(), secondGroup);
        MarketingTaskVO created = service.createTask(request(
                "发送记录聚合任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        List.of(fixture.groupLinkId(), secondGroup.groupLinkId())))));
        List<Long> targetIds = jdbc.queryForList(
                "SELECT id FROM marketing_task_target WHERE marketing_task_id = ? ORDER BY id ASC",
                Long.class,
                created.id());
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
                "群A", 1, 1, null, null, "NORMAL", 1000L);
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
                "群A", 2, 2, "MUTED", "群禁言", "BANNED", 2000L);
        insertAttempt(created.id(), targetIds.get(1), secondGroup.groupLinkId(), secondGroup.groupJid(),
                "群B", 1, 1, null, null, "NO_PERMISSION", 3000L);

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(fixture.accountId());
            assertThat(account.accountPhone()).isEqualTo(fixture.phone());
            assertThat(account.status()).isEqualTo(TARGET_STATUS_PARTIAL_FAILED);
            assertThat(account.sentMessageCount()).isEqualTo(2);
            assertThat(account.failedMessageCount()).isEqualTo(1);
            assertThat(account.lastAttemptAt()).isEqualTo(3000L);
            assertThat(account.lastSentAt()).isEqualTo(3000L);
            assertThat(account.lastReason()).isEqualTo("群禁言");
            assertThat(account.groups()).hasSize(2);
            assertThat(account.groups().get(0).groupJid()).isEqualTo(secondGroup.groupJid());
            assertThat(account.groups().get(0).membershipStatus()).isEqualTo("IN_GROUP");
            assertThat(account.groups().get(0).groupStatus()).isEqualTo("NORMAL");
            assertThat(account.groups().get(0).executionResult()).isEqualTo("SUCCESS");
            assertThat(account.groups().get(0).sentMessageCount()).isEqualTo(1);
            assertThat(account.groups().get(0).failedMessageCount()).isZero();
            assertThat(account.groups().get(1).groupJid()).isEqualTo(fixture.groupJid());
            assertThat(account.groups().get(1).membershipStatus()).isEqualTo("IN_GROUP");
            assertThat(account.groups().get(1).groupStatus()).isEqualTo("GROUP_BANNED");
            assertThat(account.groups().get(1).executionResult()).isEqualTo("FAILED");
            assertThat(account.groups().get(1).sentMessageCount()).isEqualTo(1);
            assertThat(account.groups().get(1).failedMessageCount()).isEqualTo(1);
            assertThat(account.groups().get(1).lastReason()).isEqualTo("群禁言");
        });
    }

    @Test
    void getDetail_usesLatestEndedRoundForGroupExecutionResult() {
        Fixture fixture = seedTakeoverFixture(
                "detail-execution-result",
                AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE);
        GroupFixture secondGroup = seedGroup(
                "detail-execution-result-empty",
                "120363188@g.us",
                "https://chat.whatsapp.com/detail-execution-result-empty");
        seedMembership(fixture.accountId(), secondGroup);
        MarketingTaskVO created = service.createTask(request(
                "群执行结果任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        List.of(fixture.groupLinkId(), secondGroup.groupLinkId())))));
        List<Long> targetIds = jdbc.queryForList(
                "SELECT id FROM marketing_task_target WHERE marketing_task_id = ? ORDER BY id ASC",
                Long.class,
                created.id());

        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
                "群A", 1, 1, null, null, "NORMAL", 4000L);
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
                "群A", 2, 2, "SEND_FAILED", "发送失败", "NORMAL", 2000L);
        insertAttempt(created.id(), targetIds.get(0), fixture.groupLinkId(), fixture.groupJid(),
                "群A", 3, 3, "ACCOUNT_OCCUPIED", "账号被占用", "UNCONFIRMED", 6000L);
        insertAttempt(created.id(), targetIds.get(1), secondGroup.groupLinkId(), secondGroup.groupJid(),
                "群B", 1, 3, "ACCOUNT_OCCUPIED", "账号被占用", "UNCONFIRMED", 5000L);

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.groups())
                    .filteredOn(group -> fixture.groupJid().equals(group.groupJid()))
                    .singleElement()
                    .satisfies(group -> {
                        assertThat(group.executionResult()).isEqualTo("SKIPPED");
                        assertThat(group.executionReason()).isEqualTo("账号被占用");
                        assertThat(group.skippedMessageCount()).isEqualTo(1);
                    });
            assertThat(account.groups())
                    .filteredOn(group -> secondGroup.groupJid().equals(group.groupJid()))
                    .singleElement()
                    .satisfies(group -> {
                        assertThat(group.executionResult()).isEqualTo("SKIPPED");
                        assertThat(group.executionReason()).isEqualTo("账号被占用");
                        assertThat(group.skippedMessageCount()).isEqualTo(1);
                    });
            assertThat(account.skippedMessageCount()).isEqualTo(2);
        });
        assertThat(detail.skippedMessageCount()).isEqualTo(2);
    }

    @Test
    void getDetail_rollsUpDynamicGroupExecutionResult() {
        Fixture fixture = seedTakeoverFixture(
                "detail-dynamic-execution-result",
                AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE);
        MarketingTaskVO created = service.createTask(request(
                "动态群执行结果任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(
                        fixture.accountId(),
                        "ACCOUNT_DYNAMIC",
                        List.of()))));
        Long targetId = jdbc.queryForObject(
                "SELECT id FROM marketing_task_target WHERE marketing_task_id = ?",
                Long.class,
                created.id());

        insertAttempt(created.id(), targetId, fixture.groupLinkId(), fixture.groupJid(),
                "动态群A", 1, 1, null, null, "NORMAL", 1000L);

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account ->
                assertThat(account.groups()).singleElement().satisfies(group -> {
                    assertThat(group.groupJid()).isEqualTo(fixture.groupJid());
                    assertThat(group.executionResult()).isEqualTo("SUCCESS");
                }));
    }

    @Test
    void getDetail_keepsAccountRowsWithoutSendAttempts() {
        Fixture fixture = seedFixture("detail-empty-rollup");
        MarketingTaskVO created = service.createTask(request(
                "未发送聚合任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        MarketingTaskDetailVO detail = service.getDetail(created.id());

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(fixture.accountId());
            assertThat(account.accountPhone()).isEqualTo(fixture.phone());
            assertThat(account.status()).isEqualTo(STATUS_PENDING);
            assertThat(account.sentMessageCount()).isZero();
            assertThat(account.failedMessageCount()).isZero();
            assertThat(account.skippedMessageCount()).isZero();
            assertThat(account.groups()).singleElement().satisfies(group -> {
                assertThat(group.groupJid()).isEqualTo(fixture.groupJid());
                assertThat(group.membershipStatus()).isEqualTo("IN_GROUP");
                assertThat(group.executionResult()).isNull();
                assertThat(group.sentMessageCount()).isZero();
                assertThat(group.failedMessageCount()).isZero();
                assertThat(group.skippedMessageCount()).isZero();
            });
        });
    }

    @Test
    void createTask_withoutTemplate_throwsValidation() {
        Fixture fixture = seedFixture("missing-template");
        CreateMarketingTaskDTO req = requestWithoutTemplate("缺模板任务", fixture.accountGroupId(), "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择营销模板");
    }

    @Test
    void createTask_fixedGroupDoesNotRequireActiveMembership() {
        Fixture fixture = seedFixture("missing-membership", false);

        MarketingTaskVO created = service.createTask(request(
                "无在群关系但来自实时树任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId())))));

        assertThat(created.targetPairCount()).isEqualTo(1);
        MarketingTaskDetailVO detail = service.getDetail(created.id());
        assertThat(detail.targets()).singleElement().satisfies(target -> {
            assertThat(target.groupLinkId()).isEqualTo(fixture.groupLinkId());
            assertThat(target.groupJid()).isEqualTo(fixture.groupJid());
        });
    }

    @Test
    void createTask_fixedGroupStillRejectsBaselineGroup() {
        Fixture fixture = seedFixture("baseline-fixed", false);
        seedBaseline(fixture.accountId(), "[\"" + fixture.groupJid() + "\"]");

        CreateMarketingTaskDTO req = request(
                "baseline旧群固定任务",
                fixture.accountGroupId(),
                fixture.templateId(),
                "PENDING",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或群组不可用");
    }

    private CreateMarketingTaskDTO request(String taskName,
                                           long accountGroupId,
                                           long templateId,
                                           String startMode,
                                           List<MarketingSelectionDTO> selections) {
        return requestWithTimes(taskName, accountGroupId, templateId, startMode,
                null, null, null, selections);
    }

    private CreateMarketingTaskDTO requestWithTimes(String taskName,
                                                    long accountGroupId,
                                                    long templateId,
                                                    String startMode,
                                                    Long accountGroupSendAt,
                                                    Long taskStartAt,
                                                    Long taskEndAt,
                                                    List<MarketingSelectionDTO> selections) {
        return new CreateMarketingTaskDTO(
                taskName,
                accountGroupId,
                "营销账号组",
                templateId,
                "营销模板",
                startMode,
                accountGroupSendAt,
                taskStartAt,
                taskEndAt,
                1,
                null,
                30,
                true,
                true,
                false,
                "备注",
                selections);
    }

    private CreateMarketingTaskDTO requestWithoutTemplate(String taskName,
                                                          long accountGroupId,
                                                          String startMode,
                                                          List<MarketingSelectionDTO> selections) {
        return new CreateMarketingTaskDTO(
                taskName,
                accountGroupId,
                "营销账号组",
                null,
                null,
                startMode,
                1,
                null,
                30,
                true,
                true,
                false,
                "备注",
                selections);
    }

    private CreateMarketingTaskDTO requestWithInterval(
            String taskName,
            Fixture fixture,
            BigDecimal intervalSeconds,
            List<MarketingSelectionDTO> selections) {
        return new CreateMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                "营销账号组",
                fixture.templateId(),
                "营销模板",
                "PENDING",
                null,
                null,
                null,
                1,
                intervalSeconds,
                30,
                true,
                true,
                false,
                "备注",
                selections);
    }

    private Fixture seedFixture(String suffix) {
        return seedFixture(suffix, true);
    }

    private Fixture seedFixture(String suffix, boolean withMembership) {
        return seedFixture(suffix, withMembership, 2);
    }

    private Fixture seedFixture(String suffix, boolean withMembership, int accountState) {
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
                VALUES (?, ?, ?, 1, 1, ?, ?)
                """, TEST_TENANT_ID, accountId, accountState, now, now);
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
        if (withMembership) {
            jdbc.update("""
                    INSERT INTO account_group_membership
                        (tenant_id, account_id, group_link_id, group_jid,
                         membership_status, status_source, status_updated_at,
                         last_seen_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?)
                    """, TEST_TENANT_ID, accountId, groupLinkId, groupJid, now, now, now, now);
        }
        return new Fixture(accountGroupId, templateId, accountId, phone, groupLinkId, groupUrl, groupJid);
    }

    private Fixture seedTakeoverFixture(String suffix, int accountState, int loginState) {
        Fixture fixture = seedFixture(suffix, true, accountState);
        jdbc.update("""
                UPDATE account
                SET protocol_account_id = ?,
                    group_baseline_state = 3
                WHERE id = ?
                """, "acc_" + fixture.phone(), fixture.accountId());
        jdbc.update("""
                UPDATE account_state
                SET login_state = ?
                WHERE account_id = ?
                """, loginState, fixture.accountId());
        return fixture;
    }

    private GroupFixture seedGroup(String suffix, String groupJid, String groupUrl) {
        long now = System.currentTimeMillis();
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
        return new GroupFixture(groupLinkId, groupUrl, groupJid);
    }

    private void seedMembership(long accountId, GroupFixture group) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_membership
                    (tenant_id, account_id, group_link_id, group_jid,
                     membership_status, status_source, status_updated_at,
                     joined_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'TEST_FIXTURE', ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, group.groupLinkId(), group.groupJid(),
                now, now, now, now, now);
    }

    private void insertAttempt(long taskId,
                               long targetId,
                               long groupLinkId,
                               String groupJid,
                               String groupName,
                               long roundNo,
                               int status,
                               String reasonCode,
                               String reasonMessage,
                               String groupStatus,
                               long resultAt) {
        jdbc.update("""
                INSERT INTO marketing_task_send_attempt
                    (tenant_id, marketing_task_id, target_id, group_link_id, group_jid, group_name,
                     round_no, attempt_no, is_retry, command_id, status, reason_code, reason_message,
                     group_status, group_status_reason, group_status_checked_at,
                     submitted_at, result_at, attempted_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, taskId, targetId, groupLinkId, groupJid, groupName, roundNo,
                "cmd-" + targetId + "-" + resultAt, status, reasonCode, reasonMessage,
                groupStatus, "TEST_STATUS", resultAt - 15,
                resultAt - 10, resultAt, resultAt - 20, resultAt - 30);
    }

    private void seedBaseline(long accountId, String baselineGroupJids) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, ?, JSON_LENGTH(?), ?, ?, ?)
                """, TEST_TENANT_ID, accountId, baselineGroupJids, baselineGroupJids, now, now, now);
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

    private record GroupFixture(
            long groupLinkId,
            String groupUrl,
            String groupJid) {
    }
}
