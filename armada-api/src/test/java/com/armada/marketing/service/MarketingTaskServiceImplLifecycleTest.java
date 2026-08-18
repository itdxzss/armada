package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.vo.MarketingTaskAccountGroupStatRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.service.impl.MarketingAccountTreeRealtimeService;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 营销任务生命周期门禁单测。
 *
 * <p>状态流转 SQL 由 DbTest 覆盖；本类聚焦启动入口在更新任务状态前必须完成的业务校验。</p>
 */
@ExtendWith(MockitoExtension.class)
class MarketingTaskServiceImplLifecycleTest {

    private static final long TASK_ID = 42L;
    private static final long TEMPLATE_ID = 77L;

    @Mock
    private MarketingTaskMapper taskMapper;

    @Mock
    private MarketingTemplateMapper templateMapper;

    @Mock
    private MarketingTemplateService templateService;

    @Mock
    private MarketingAccountTreeRealtimeService accountTreeRealtimeService;

    @Mock
    private MarketingAccountOccupancyService occupancyService;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private MarketingTaskServiceImpl service;

    @Test
    void getDetailBatchLoadsLoginStateAndNormalizesOneEffectiveAttempt() {
        MarketingTask task = new MarketingTask();
        task.setId(TASK_ID);
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(501L);
        target.setAccountId(31L);
        target.setAccountPhone("923300000031");
        target.setStatus(1);
        MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
        group.setAccountId(31L);
        group.setGroupJid("120363031@g.us");
        group.setLatestAttemptStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setReasonCode("ACCOUNT_BANNED");
        group.setReasonMessage("forbidden");
        group.setGroupStatus("BANNED");
        group.setGroupStatusReason("CHAT_SUSPENDED");
        group.setMembershipStatus(3);
        group.setLatestExecutionStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setExecutionReasonCode("ACCOUNT_BANNED");
        group.setExecutionReasonMessage("账号封禁");
        group.setSentMessageCount(2);
        group.setFailedMessageCount(1);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(TASK_ID)).thenReturn(List.of(target));
        when(taskMapper.selectAccountGroupStatsByTaskId(TASK_ID)).thenReturn(List.of(group));
        when(accountService.getLoginStatesByIds(List.of(31L))).thenReturn(Map.of(31L, 1));

        var detail = service.getDetail(TASK_ID);

        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.loginState()).isEqualTo(1);
            assertThat(account.sentMessageCount()).isEqualTo(2);
            assertThat(account.groups()).singleElement().satisfies(item -> {
                assertThat(item.membershipStatus()).isEqualTo("KICKED_OUT");
                assertThat(item.groupStatus()).isEqualTo("ACCOUNT_BANNED");
                assertThat(item.executionResult()).isEqualTo("FAILED");
                assertThat(item.executionReason()).isEqualTo("账号封禁");
            });
        });
        verify(accountService).getLoginStatesByIds(List.of(31L));
    }

    @Test
    void getDetailKeepsHistoricalGroupStatusAndLatestOfflineReasonIndependent() {
        MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
        group.setAccountId(31L);
        group.setGroupJid("120363031@g.us");
        group.setLatestAttemptStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setReasonCode("SEND_FAILED");
        group.setReasonMessage("群组不可发送");
        group.setGroupStatus("BANNED");
        group.setGroupStatusReason("CHAT_SUSPENDED");
        group.setLatestExecutionStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setExecutionReasonCode("ACCOUNT_OFFLINE");
        group.setExecutionReasonMessage("安卓账号当前不在线");
        group.setExecutionGroupStatus("UNCONFIRMED");
        group.setExecutionGroupStatusReason("STATUS_RESOLUTION_UNAVAILABLE");
        stubDetail(detailTask(), detailTarget(), group);

        var detail = service.getDetail(TASK_ID);

        assertThat(detail.accountTargets()).singleElement()
                .satisfies(account -> assertThat(account.groups()).singleElement().satisfies(item -> {
                    assertThat(item.groupStatus()).isEqualTo("GROUP_BANNED");
                    assertThat(item.executionResult()).isEqualTo("FAILED");
                    assertThat(item.executionReason()).isEqualTo("安卓账号当前不在线");
                }));
    }

    @Test
    void getDetailKeepsMembershipProtocolStatusAndSkippedExecutionIndependent() {
        MarketingTask task = detailTask();
        MarketingTaskTarget target = detailTarget();
        MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
        group.setAccountId(31L);
        group.setGroupJid("120363031@g.us");
        group.setMembershipStatus(4);
        group.setLatestAttemptStatus(MarketingSendAttemptStatus.SUCCESS.code());
        group.setLatestExecutionStatus(MarketingSendAttemptStatus.SKIPPED.code());
        group.setExecutionReasonCode("LEFT");
        group.setExecutionReasonMessage("账号已主动退出群聊");
        group.setSentMessageCount(1);
        group.setSkippedMessageCount(1);
        stubDetail(task, target, group);

        var detail = service.getDetail(TASK_ID);

        assertThat(detail.skippedMessageCount()).isEqualTo(1);
        assertThat(detail.accountTargets()).singleElement().satisfies(account -> {
            assertThat(account.skippedMessageCount()).isEqualTo(1);
            assertThat(account.groups()).singleElement().satisfies(item -> {
                assertThat(item.membershipStatus()).isEqualTo("LEFT");
                assertThat(item.groupStatus()).isEqualTo("NORMAL");
                assertThat(item.executionResult()).isEqualTo("SKIPPED");
                assertThat(item.executionReason()).isEqualTo("账号已主动退出群聊");
                assertThat(item.sentMessageCount()).isEqualTo(1);
                assertThat(item.failedMessageCount()).isZero();
                assertThat(item.skippedMessageCount()).isEqualTo(1);
            });
        });
    }

    @Test
    void getDetailUsesHistoricalKickedOutResultWhenMembershipRowIsMissing() {
        MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
        group.setAccountId(31L);
        group.setGroupJid("120363031@g.us");
        group.setLatestAttemptStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setReasonCode("ACCOUNT_NOT_PARTICIPANT");
        group.setLatestExecutionStatus(MarketingSendAttemptStatus.FAILED.code());
        group.setExecutionReasonCode("ACCOUNT_NOT_PARTICIPANT");
        stubDetail(detailTask(), detailTarget(), group);

        var detail = service.getDetail(TASK_ID);

        assertThat(detail.accountTargets()).singleElement()
                .satisfies(account -> assertThat(account.groups()).singleElement()
                        .satisfies(item -> assertThat(item.membershipStatus()).isEqualTo("KICKED_OUT")));
    }

    @ParameterizedTest
    @CsvSource({"KICKED_OUT", "LEFT", "NOT_IN_GROUP"})
    void getDetailUsesSkippedExitReasonWhenMembershipRowIsMissing(String reasonCode) {
        MarketingTaskAccountGroupStatRow group = new MarketingTaskAccountGroupStatRow();
        group.setAccountId(31L);
        group.setGroupJid("120363031@g.us");
        group.setLatestExecutionStatus(MarketingSendAttemptStatus.SKIPPED.code());
        group.setExecutionReasonCode(reasonCode);
        group.setExecutionReasonMessage("账号当前不在群内");
        stubDetail(detailTask(), detailTarget(), group);

        var detail = service.getDetail(TASK_ID);

        assertThat(detail.accountTargets()).singleElement()
                .satisfies(account -> assertThat(account.groups()).singleElement()
                        .satisfies(item -> assertThat(item.membershipStatus()).isEqualTo(reasonCode)));
    }

    private void stubDetail(MarketingTask task,
                            MarketingTaskTarget target,
                            MarketingTaskAccountGroupStatRow group) {
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(taskMapper.selectTargetsByTaskId(TASK_ID)).thenReturn(List.of(target));
        when(taskMapper.selectAccountGroupStatsByTaskId(TASK_ID)).thenReturn(List.of(group));
        when(accountService.getLoginStatesByIds(List.of(31L))).thenReturn(Map.of(31L, 1));
    }

    private static MarketingTask detailTask() {
        MarketingTask task = new MarketingTask();
        task.setId(TASK_ID);
        return task;
    }

    private static MarketingTaskTarget detailTarget() {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(501L);
        target.setAccountId(31L);
        target.setAccountPhone("923300000031");
        target.setStatus(1);
        return target;
    }

    @Test
    void createTask_futureTaskPersistsAccountGroupIntervalAndLocksAccountsAfterTargetsPersisted() {
        AtomicReference<MarketingTask> insertedTask = new AtomicReference<>();
        when(templateMapper.selectByIdForUpdate(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.selectAccountTargetCandidate(
                eq(12L),
                eq(31L),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(accountCandidate());
        doAnswer(invocation -> {
            MarketingTask task = invocation.getArgument(0);
            task.setId(TASK_ID);
            insertedTask.set(task);
            return 1;
        }).when(taskMapper).insertTask(org.mockito.ArgumentMatchers.any());
        when(taskMapper.selectTaskById(TASK_ID)).thenAnswer(invocation -> insertedTask.get());
        long now = System.currentTimeMillis();
        CreateMarketingTaskDTO request = new CreateMarketingTaskDTO(
                "未来执行任务", 12L, "营销账号组", TEMPLATE_ID, "营销模板", "PENDING",
                null, now + 60_000L, now + 600_000L,
                1, new BigDecimal("3.0"), 30, true, true, false,
                true, 12, "HOUR", null,
                java.util.List.of(new MarketingSelectionDTO(31L, "ACCOUNT_DYNAMIC", java.util.List.of())));

        var created = service.createTask(request);

        assertThat(insertedTask.get().getAccountGroupSendIntervalMs()).isEqualTo(3_000);
        assertThat(insertedTask.get().getNewGroupDelayEnabled()).isTrue();
        assertThat(insertedTask.get().getNewGroupDelayValue()).isEqualTo(12);
        assertThat(insertedTask.get().getNewGroupDelayUnit()).isEqualTo(2);
        assertThat(created.accountGroupSendIntervalSeconds()).isEqualByComparingTo("3.0");
        assertThat(created.newGroupDelayUnit()).isEqualTo("HOUR");
        verify(templateMapper).selectByIdForUpdate(TEMPLATE_ID);
        verify(occupancyService).lockTaskAccountsOrThrow(
                eq(insertedTask.get()), anyLong());
    }

    @Test
    void createTask_missingAccountGroupIntervalDefaultsToHalfSecond() {
        AtomicReference<MarketingTask> insertedTask = new AtomicReference<>();
        when(templateMapper.selectByIdForUpdate(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.selectAccountTargetCandidate(
                eq(12L),
                eq(31L),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(accountCandidate());
        doAnswer(invocation -> {
            MarketingTask task = invocation.getArgument(0);
            task.setId(TASK_ID);
            insertedTask.set(task);
            return 1;
        }).when(taskMapper).insertTask(org.mockito.ArgumentMatchers.any());
        when(taskMapper.selectTaskById(TASK_ID)).thenAnswer(invocation -> insertedTask.get());

        var created = service.createTask(requestWithInterval(null));

        assertThat(insertedTask.get().getAccountGroupSendIntervalMs()).isEqualTo(500);
        assertThat(created.accountGroupSendIntervalSeconds()).isEqualByComparingTo("0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.55", "3.1"})
    void createTask_invalidAccountGroupIntervalIsRejected(String interval) {
        assertThatThrownBy(() -> service.createTask(requestWithInterval(new BigDecimal(interval))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单账号下群组发送间隔必须为0.5到3秒，最多一位小数");

        verify(taskMapper, never()).insertTask(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @CsvSource({
            "MINUTE,61,分钟延迟时长必须为1到60的正整数",
            "HOUR,25,小时延迟时长必须为1到24的正整数"
    })
    void createTask_invalidNewGroupDelayIsRejected(String unit, int value, String message) {
        assertThatThrownBy(() -> service.createTask(requestWithDelay(value, unit)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(message);

        verify(taskMapper, never()).insertTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startTask_deletedTemplate_isRejectedWithoutChangingTaskState() {
        long now = System.currentTimeMillis();
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(
                MarketingTaskStatus.PENDING.code(), now + 60_000L, now + 600_000L));

        assertThatThrownBy(() -> service.startTask(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("营销模板已删除，任务不可启动");

        verify(templateMapper).selectById(TEMPLATE_ID);
        verify(taskMapper, never()).startPendingTask(anyLong(), anyLong());
    }

    @Test
    void startTask_beforeExecutionWindowKeepsPendingWithoutDatabaseMutation() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.PENDING.code(), now + 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());

        service.startTask(TASK_ID);

        verify(taskMapper, never()).startPendingTask(anyLong(), anyLong());
        verify(occupancyService, never()).releaseTaskAccounts(anyLong());
    }

    @Test
    void startTask_insideExecutionWindowStartsPendingTaskWithoutReacquiringAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.PENDING.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.startPendingTask(eq(TASK_ID), anyLong())).thenReturn(1);

        service.startTask(TASK_ID);

        verify(taskMapper).startPendingTask(eq(TASK_ID), anyLong());
        verify(occupancyService, never()).acquireAndLoadTaskAccounts(
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void pauseTask_sendingTaskKeepsOwnedAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.SENDING.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(taskMapper.pauseSendingTask(eq(TASK_ID), anyLong())).thenReturn(1);

        service.pauseTask(TASK_ID);

        verify(occupancyService, never()).releaseTaskAccounts(anyLong());
    }

    @Test
    void resumeTask_pausedTaskInsideWindowResumesWithoutReacquiringAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.PAUSED.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template());
        when(taskMapper.resumePausedTask(eq(TASK_ID), anyLong())).thenReturn(1);

        service.resumeTask(TASK_ID);

        verify(occupancyService, never()).acquireAndLoadTaskAccounts(
                org.mockito.ArgumentMatchers.any(), anyLong());
        verify(occupancyService, never()).releaseTaskAccounts(anyLong());
    }

    @Test
    void closeTask_activeTaskReleasesOwnedAccounts() {
        long now = System.currentTimeMillis();
        MarketingTask task = task(MarketingTaskStatus.SENDING.code(), now - 60_000L, now + 600_000L);
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task);
        when(taskMapper.closeActiveTask(eq(TASK_ID), anyLong())).thenReturn(1);

        service.closeTask(TASK_ID);

        verify(taskMapper).markTaskWaitingAttemptsSkipped(
                eq(TASK_ID), eq("TASK_CLOSED"), eq("营销任务已关闭"), anyLong());
        verify(occupancyService).releaseTaskAccounts(TASK_ID);
    }

    @Test
    void completedTaskCannotBeStartedOrClosed() {
        long now = System.currentTimeMillis();
        when(taskMapper.selectTaskById(TASK_ID)).thenReturn(task(
                MarketingTaskStatus.COMPLETED.code(), now - 600_000L, now - 60_000L));

        assertThatThrownBy(() -> service.startTask(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("只有未启动的任务可以启动");
        assertThatThrownBy(() -> service.closeTask(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已完成或已关闭的任务不可手动关闭");

        verify(taskMapper, never()).startPendingTask(anyLong(), anyLong());
        verify(taskMapper, never()).closeActiveTask(anyLong(), anyLong());
    }

    private static MarketingTask task(int status, long taskStartAt, long taskEndAt) {
        MarketingTask task = new MarketingTask();
        task.setId(TASK_ID);
        task.setTenantId(1L);
        task.setMarketingTemplateId(TEMPLATE_ID);
        task.setStatus(status);
        task.setTaskStartAt(taskStartAt);
        task.setTaskEndAt(taskEndAt);
        return task;
    }

    private static MarketingTemplate template() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(TEMPLATE_ID);
        template.setTemplateName("营销模板");
        return template;
    }

    private static MarketingTargetCandidateRow accountCandidate() {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(31L);
        row.setAccountPhone("923100000031");
        return row;
    }

    private static CreateMarketingTaskDTO requestWithInterval(BigDecimal interval) {
        return new CreateMarketingTaskDTO(
                "间隔测试任务", 12L, "营销账号组", TEMPLATE_ID, "营销模板", "PENDING",
                null, null, null, 1, interval, 30, true, true, false,
                false, 30, "MINUTE", null,
                java.util.List.of(new MarketingSelectionDTO(31L, "ACCOUNT_DYNAMIC", java.util.List.of())));
    }

    private static CreateMarketingTaskDTO requestWithDelay(int value, String unit) {
        return new CreateMarketingTaskDTO(
                "延迟校验任务", 12L, "营销账号组", TEMPLATE_ID, "营销模板", "PENDING",
                null, null, null, 1, new BigDecimal("0.5"), 30, true, true, false,
                true, value, unit, null,
                java.util.List.of(new MarketingSelectionDTO(31L, "ACCOUNT_DYNAMIC", java.util.List.of())));
    }
}
