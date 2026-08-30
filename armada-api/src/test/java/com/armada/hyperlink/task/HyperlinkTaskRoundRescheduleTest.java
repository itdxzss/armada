package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.hyperlink.task.service.HyperlinkTaskConfigurationFactory;
import com.armada.hyperlink.task.service.HyperlinkTaskLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteGuardService;
import com.armada.hyperlink.task.service.HyperlinkTaskStoreService;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** 已准备且未开始任务的首轮排期随已保存配置原子更新。 */
class HyperlinkTaskRoundRescheduleTest {

    @ParameterizedTest
    @CsvSource({
            "1,0,SCHEDULED,60,3600000",
            "2,60,NOW,0,0",
            "2,60,SCHEDULED,120,7200000"
    })
    void updateReschedulesReadyFirstRoundFromTheCurrentEditTime(int oldMode, int oldDelay,
            HyperlinkTaskStartMode newMode, int newDelay, long expectedOffset) {
        Fixture fixture = fixture(oldMode, oldDelay, newMode, newDelay);
        when(fixture.rounds().rescheduleUnconsumedFirstRound(eq(11L), anyLong(), anyLong()))
                .thenReturn(1);

        fixture.service().update(11L, fixture.request(), principal());

        ArgumentCaptor<Long> scheduledAt = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> updatedAt = ArgumentCaptor.forClass(Long.class);
        verify(fixture.rounds()).rescheduleUnconsumedFirstRound(
                eq(11L), scheduledAt.capture(), updatedAt.capture());
        assertThat(scheduledAt.getValue() - updatedAt.getValue()).isEqualTo(expectedOffset);
        verify(fixture.audit()).record(any());
    }

    @Test
    void updateReturnsStateConflictWhenDueStartWinsTheRoundCas() {
        Fixture fixture = fixture(HyperlinkTaskStartMode.SCHEDULED.code(), 60,
                HyperlinkTaskStartMode.NOW, 0);
        when(fixture.rounds().rescheduleUnconsumedFirstRound(eq(11L), anyLong(), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> fixture.service().update(11L, fixture.request(), principal()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(
                                ErrorCode.HYPERLINK_TASK_STATE_CONFLICT.code()));

        verify(fixture.audit(), never()).record(any());
    }

    private Fixture fixture(int oldMode, int oldDelay,
            HyperlinkTaskStartMode newMode, int newDelay) {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        HyperlinkMessageContent content = mock(HyperlinkMessageContent.class);
        HyperlinkTaskConfigurationFactory.Normalized normalized =
                new HyperlinkTaskConfigurationFactory.Normalized(
                        HyperlinkTaskMode.INSTANT, newMode, content,
                        mock(HyperlinkAccountFilterDTO.class), null, 0, 500, 700,
                        1, 1, 0, newDelay, true, false);
        HyperlinkTask existing = task(oldMode, oldDelay);
        HyperlinkTask replacement = task(newMode.code(), newDelay);
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setEnabled(true);
        runtime.setRunStatus(0);
        runtime.setProvisionStatus(HyperlinkProvisionStatus.READY.code());
        when(request.version()).thenReturn(3);
        when(request.sourceTaskId()).thenReturn(null);
        when(request.sourceStrategyId()).thenReturn(null);
        when(factory.normalizeForUpdate(request, 3)).thenReturn(normalized);
        when(factory.task(eq(request), eq(normalized), eq(8L), anyLong()))
                .thenReturn(replacement);
        when(factory.content(eq(11L), eq(content), anyLong()))
                .thenReturn(new HyperlinkTaskContent());
        when(factory.frozenScopeChanged(existing, replacement)).thenReturn(false);
        when(store.requireTask(11L)).thenReturn(existing);
        HyperlinkTaskContent existingContent = new HyperlinkTaskContent();
        existingContent.setMessageType(3);
        when(store.requireContent(11L)).thenReturn(existingContent);
        when(store.requireRuntime(11L)).thenReturn(runtime);
        when(store.receipt(11L)).thenReturn(new HyperlinkTaskMutationReceiptVO(11L,
                HyperlinkProvisionStatus.READY, true, 0, 4, null, null, null));
        HyperlinkTaskLifecycleService service = new HyperlinkTaskLifecycleService(factory, store,
                mock(HyperlinkTaskQuoteGuardService.class),
                mock(HyperlinkProvisionFactService.class),
                mock(HyperlinkCleanupStartService.class), rounds, audit,
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class),
                mock(com.armada.hyperlink.strategy.service.HyperlinkTaskStrategyService.class));
        return new Fixture(service, request, rounds, audit);
    }

    private HyperlinkTask task(int startMode, int delayMinutes) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setCreatedBy(8L);
        task.setVersion(3);
        task.setStartMode(startMode);
        task.setTaskDelayMinutes(delayMinutes);
        return task;
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, 7L, "u", "U", "t", "T", List.of(), List.of());
    }

    private record Fixture(HyperlinkTaskLifecycleService service, HyperlinkTaskSaveDTO request,
                           HyperlinkTaskRoundMapper rounds, HyperlinkTaskAuditPort audit) { }
}
