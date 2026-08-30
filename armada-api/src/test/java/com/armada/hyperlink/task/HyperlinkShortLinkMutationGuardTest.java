package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
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

/** 缺少公网基址时，短链任务不得进入任何本地运行状态。 */
class HyperlinkShortLinkMutationGuardTest {

    @Test
    void enabledCreateFailsBeforeTaskAggregateIsInserted() {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProvisionFactService provision = mock(HyperlinkProvisionFactService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        when(request.version()).thenReturn(null);
        when(request.sourceTaskId()).thenReturn(null);
        when(request.sourceStrategyId()).thenReturn(null);
        HyperlinkTaskConfigurationFactory.Normalized normalized = normalized(true);
        when(factory.normalizeForCreate(request)).thenReturn(normalized);
        when(factory.task(eq(request), eq(normalized), anyLong(), anyLong()))
                .thenReturn(shortLinkTask());
        when(factory.runtime(eq(0), eq(true), anyLong())).thenReturn(runtime(false, 0, 1));
        HyperlinkTaskLifecycleService service = new HyperlinkTaskLifecycleService(factory, store,
                quote, provision, mock(HyperlinkCleanupStartService.class),
                mock(HyperlinkTaskRoundMapper.class), audit,
                new HyperlinkShortLinkGuard(""),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class),
                mock(com.armada.hyperlink.strategy.service.HyperlinkTaskStrategyService.class));

        assertGuardUnavailable(() -> service.create(request, principal()));

        verify(store, never()).insert(any(), any(), any());
        verifyNoInteractions(provision, audit);
    }

    @Test
    void enabledUpdateFailsBeforeVersionOrRuntimeChanges() {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkProvisionFactService provision = mock(HyperlinkProvisionFactService.class);
        HyperlinkCleanupStartService cleanup = mock(HyperlinkCleanupStartService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        when(request.version()).thenReturn(3);
        when(request.sourceTaskId()).thenReturn(null);
        when(request.sourceStrategyId()).thenReturn(null);
        HyperlinkTaskConfigurationFactory.Normalized normalized = normalized(true);
        when(factory.normalizeForUpdate(request, 3)).thenReturn(normalized);
        when(store.requireTask(11L)).thenReturn(task(false));
        HyperlinkTaskContent existingContent = new HyperlinkTaskContent();
        existingContent.setMessageType(3);
        when(store.requireContent(11L)).thenReturn(existingContent);
        when(store.requireRuntime(11L)).thenReturn(runtime(false, 0, 0));
        when(factory.task(eq(request), eq(normalized), anyLong(), anyLong()))
                .thenReturn(shortLinkTask());
        HyperlinkTaskLifecycleService service = new HyperlinkTaskLifecycleService(factory, store,
                mock(HyperlinkTaskQuoteGuardService.class), provision, cleanup,
                mock(HyperlinkTaskRoundMapper.class), audit,
                new HyperlinkShortLinkGuard(""),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class),
                mock(com.armada.hyperlink.strategy.service.HyperlinkTaskStrategyService.class));

        assertGuardUnavailable(() -> service.update(11L, request, principal()));

        verify(store, never()).update(any(), any(), anyInt());
        verify(store, never()).beginRebuild(anyLong(), anyBoolean(), anyLong());
        verify(store, never()).transition(anyLong(), anyBoolean(), anyInt(), anyBoolean(),
                anyInt(), anyInt(), anyLong());
        verify(cleanup, never()).begin(anyLong(), anyBoolean(), anyLong());
        verifyNoInteractions(provision, audit);
    }

    @Test
    void startFailsBeforeVersionTransitionOrRoundDispatchIsScheduled() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkProvisionFactService provision = mock(HyperlinkProvisionFactService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        when(store.requireTask(11L)).thenReturn(shortLinkTask());
        when(store.requireRuntime(11L)).thenReturn(runtime(true, 0,
                HyperlinkProvisionStatus.READY.code()));
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store,
                mock(HyperlinkTaskQuoteGuardService.class), provision, rounds,
                mock(HyperlinkCleanupStartService.class), audit, new HyperlinkShortLinkGuard(""),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        assertGuardUnavailable(() -> service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 3, "quote"), principal()));

        verify(store, never()).incrementVersion(anyLong(), anyInt(), anyLong());
        verify(store, never()).transition(anyLong(), anyBoolean(), anyInt(), anyBoolean(),
                anyInt(), anyInt(), anyLong());
        verify(rounds, never()).scheduleNow(anyLong(), anyLong());
        verifyNoInteractions(provision, audit);
    }

    private void assertGuardUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(
                                ErrorCode.HYPERLINK_DISPATCH_GUARD_UNAVAILABLE.code()));
    }

    private HyperlinkTaskConfigurationFactory.Normalized normalized(boolean enabled) {
        return new HyperlinkTaskConfigurationFactory.Normalized(
                HyperlinkTaskMode.INSTANT, HyperlinkTaskStartMode.NOW,
                mock(HyperlinkMessageContent.class), mock(HyperlinkAccountFilterDTO.class),
                null, 0, 500, 700, 1, 1, 0, 0, enabled, true);
    }

    private HyperlinkTask shortLinkTask() {
        return task(true);
    }

    private HyperlinkTask task(boolean shortLinkEnabled) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setCreatedBy(8L);
        task.setVersion(3);
        task.setShortLinkEnabled(shortLinkEnabled);
        task.setConcurrentNum(1);
        return task;
    }

    private HyperlinkTaskRuntime runtime(boolean enabled, int runStatus, int provisionStatus) {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setEnabled(enabled);
        runtime.setRunStatus(runStatus);
        runtime.setProvisionStatus(provisionStatus);
        return runtime;
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, 7L, "u", "U", "t", "T", List.of(), List.of());
    }
}
