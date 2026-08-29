package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.contact.task.service.impl.ContactTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactTaskServiceImplTest {

    private static final long NOW = 1_756_345_678_901L;
    private static final long TENANT = 1L;
    private static final long USER = 88L;

    private ContactFriendTaskMapper taskMapper;
    private ContactFriendTaskAccountMapper accountMapper;
    private ContactTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(ContactFriendTaskMapper.class);
        accountMapper = mock(ContactFriendTaskAccountMapper.class);
        service = new ContactTaskServiceImpl(
                taskMapper,
                accountMapper,
                new ContactTaskFormValidator(),
                new ContactAccountFilterNormalizer(new ObjectMapper()),
                () -> TENANT,
                () -> NOW);
    }

    private static ContactTaskFormDTO form(String startMode, int delay, int enabled) {
        return new ContactTaskFormDTO(
                "任务A", 1, null, null, null, "文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, startMode, delay, enabled, "{\"country_iso2s\":[\"cn\"]}");
    }

    private static ContactFriendTask task(int runStatus) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(9L);
        task.setTenantId(TENANT);
        task.setRunStatus(runStatus);
        task.setMessageType(1);
        return task;
    }

    @Test
    void createPersistsNormalizedFilterAndTenantAndCreator() {
        service.create(form("now", 0, 0), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        ContactFriendTask row = saved.getValue();
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getCreatedBy()).isEqualTo(USER);
        assertThat(row.getRunStatus()).isEqualTo(ContactTaskRunStatus.NOT_STARTED.code());
        // 筛选条件必须是归一化后的 camelCase 白名单 JSON
        assertThat(row.getAccountFilter()).contains("countryIso2s").contains("CN");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void scheduledEnabledTaskGetsComputedStartTime() {
        service.create(form("scheduled", 30, 1), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isEqualTo(NOW + 30 * 60_000L);
    }

    @Test
    void immediateTaskStartsNow() {
        service.create(form("now", 0, 1), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isEqualTo(NOW);
    }

    @Test
    void disabledTaskHasNoStartTime() {
        service.create(form("now", 0, 0), USER);

        ArgumentCaptor<ContactFriendTask> saved = ArgumentCaptor.forClass(ContactFriendTask.class);
        verify(taskMapper).insert(saved.capture());
        assertThat(saved.getValue().getTaskStartAt()).isNull();
    }

    @Test
    void updateRejectsStartedTasks() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));

        assertThatThrownBy(() -> service.update(9L, form("now", 0, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已开始");

        verify(taskMapper, never()).updateForm(any());
    }

    @Test
    void updateRejectsMessageTypeChange() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));

        ContactTaskFormDTO changed = new ContactTaskFormDTO(
                "任务A", 0, "标题", "描述", "https://a.com", "文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 0, "{}");

        assertThatThrownBy(() -> service.update(9L, changed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息类型");
    }

    @Test
    void updateAcceptsNotStartedTaskWithSameMessageType() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(taskMapper.updateForm(any())).thenReturn(1);

        service.update(9L, form("now", 0, 0));

        verify(taskMapper).updateForm(any());
    }

    @Test
    void detailAndUpdateRejectMissingTask() {
        when(taskMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(404L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(404L, form("now", 0, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void startMovesNotStartedToRunning() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(1);

        service.action(9L, "start");

        verify(taskMapper).updateRunStatus(
                9L,
                ContactTaskRunStatus.NOT_STARTED.code(),
                ContactTaskRunStatus.RUNNING.code(),
                NOW,
                NOW);
    }

    @Test
    void stopFromPausedIsAllowedAndClearsNextRound() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.PAUSED.code()));
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(1);

        service.action(9L, "stop");

        verify(taskMapper).updateRunStatus(
                9L,
                ContactTaskRunStatus.PAUSED.code(),
                ContactTaskRunStatus.STOPPED.code(),
                null,
                NOW);
    }

    @Test
    void illegalTransitionIsRejectedBeforeTouchingTheDatabase() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.STOPPED.code()));

        assertThatThrownBy(() -> service.action(9L, "resume"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");

        verify(taskMapper, never()).updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong());
    }

    @Test
    void concurrentStatusChangeIsReportedAsConflict() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));
        // 条件更新命中 0 行 = 状态已被别的请求改掉
        when(taskMapper.updateRunStatus(anyLong(), anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.action(9L, "pause"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态已变更");
    }

    @Test
    void unknownActionIsRejected() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.RUNNING.code()));

        assertThatThrownBy(() -> service.action(9L, "delete"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accountDataPageIsEmptyUntilTheEngineExpandsIt() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(accountMapper.countByTaskId(9L)).thenReturn(0L);
        when(accountMapper.selectPage(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertThat(service.accountData(9L, null, null, 1, 20).total()).isZero();
    }

    @Test
    void accountDataOnlyPassesThroughWhitelistedSortColumns() {
        when(taskMapper.selectById(9L)).thenReturn(task(ContactTaskRunStatus.NOT_STARTED.code()));
        when(accountMapper.countByTaskId(9L)).thenReturn(0L);
        when(accountMapper.selectPage(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.accountData(9L, "sentNum", "asc", 1, 20);
        verify(accountMapper).selectPage(9L, "sentNum", "asc", 0, 20);

        // 非白名单列必须被抹成 null，交给 XML 兜底按 id 排序
        service.accountData(9L, "1=1", "; DROP", 1, 20);
        verify(accountMapper).selectPage(9L, null, "desc", 0, 20);
    }
}
