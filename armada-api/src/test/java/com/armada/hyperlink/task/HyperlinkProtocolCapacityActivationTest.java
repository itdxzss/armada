package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
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
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkAccountCandidateSelector;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 每个真实启用入口必须在产生任务、计费、准备或版本事实前重检协议容量。 */
class HyperlinkProtocolCapacityActivationTest {

    @Test
    void enabledCreateChecksCapacityBeforeQuoteAndInsert() {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProtocolCapacityService capacity = rejectingCapacity();
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        when(request.version()).thenReturn(null);
        when(request.sourceTaskId()).thenReturn(null);
        when(factory.normalizeForCreate(request)).thenReturn(normalized(true, 16));
        HyperlinkTaskLifecycleService service = lifecycle(factory, store, quote, capacity);

        assertCapacityFailure(() -> service.create(request, principal()));

        verify(capacity).requireSufficient(16);
        verifyNoInteractions(quote);
        verify(store, never()).insert(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyEnabledUpdateChecksCapacityWhetherPreviouslyDisabledOrEnabled(
            boolean previouslyEnabled) {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProtocolCapacityService capacity = rejectingCapacity();
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        HyperlinkTaskContent content = new HyperlinkTaskContent();
        content.setMessageType(1);
        when(request.version()).thenReturn(3);
        when(request.sourceTaskId()).thenReturn(null);
        when(factory.normalizeForUpdate(request, 1)).thenReturn(normalized(true, 16));
        when(store.requireTask(11L)).thenReturn(task(16));
        when(store.requireContent(11L)).thenReturn(content);
        when(store.requireRuntime(11L)).thenReturn(runtime(previouslyEnabled));
        HyperlinkTaskLifecycleService service = lifecycle(factory, store, quote, capacity);

        assertCapacityFailure(() -> service.update(11L, request, principal()));

        verify(capacity).requireSufficient(16);
        verifyNoInteractions(quote);
        verify(store, never()).update(any(), any(), anyInt());
    }

    @Test
    void startOfSavedTaskChecksCapacityBeforeQuoteVersionAndProvisioning() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProtocolCapacityService capacity = rejectingCapacity();
        when(store.requireTask(11L)).thenReturn(task(16));
        when(store.requireRuntime(11L)).thenReturn(runtime(false));
        HyperlinkTaskActionService service = action(store, quote, capacity);

        assertCapacityFailure(() -> service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 3, "quote"), principal()));

        verify(capacity).requireSufficient(16);
        verifyNoInteractions(quote);
        verify(store, never()).incrementVersion(anyLong(), anyInt(), anyLong());
    }

    @Test
    void zeroProtocolsFailsAndExactCapacityBoundaryPasses() {
        HyperlinkAccountCandidateSelector selector = mock(HyperlinkAccountCandidateSelector.class);
        HyperlinkProtocolCapacityService capacity = new HyperlinkProtocolCapacityService(selector);
        when(selector.protocolCount()).thenReturn(0, 2, 2);

        assertCapacityFailure(() -> capacity.requireSufficient(1));
        assertThatCode(() -> capacity.requireSufficient(30)).doesNotThrowAnyException();
        assertCapacityFailure(() -> capacity.requireSufficient(31));
    }

    @Test
    void historicalDoubleImageStartFailsBeforeVersionMutation() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProtocolCapacityService capacity = mock(HyperlinkProtocolCapacityService.class);
        HyperlinkTaskContent content = new HyperlinkTaskContent();
        content.setMessageType(2);
        when(store.requireTask(11L)).thenReturn(task(1));
        when(store.requireRuntime(11L)).thenReturn(runtime(false));
        when(store.requireContent(11L)).thenReturn(content);
        HyperlinkTaskActionService service = action(store, quote, capacity);

        assertThatThrownBy(() -> service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 3, "quote"), principal()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(exception.getMessage()).contains("历史双图文");
                });

        verify(store, never()).incrementVersion(anyLong(), anyInt(), anyLong());
    }

    private HyperlinkTaskLifecycleService lifecycle(HyperlinkTaskConfigurationFactory factory,
            HyperlinkTaskStoreService store, HyperlinkTaskQuoteGuardService quote,
            HyperlinkProtocolCapacityService capacity) {
        return new HyperlinkTaskLifecycleService(factory, store, quote,
                mock(HyperlinkProvisionFactService.class), mock(HyperlinkCleanupStartService.class),
                mock(HyperlinkTaskRoundMapper.class), mock(HyperlinkTaskAuditPort.class),
                mock(HyperlinkShortLinkGuard.class), capacity);
    }

    private HyperlinkTaskActionService action(HyperlinkTaskStoreService store,
            HyperlinkTaskQuoteGuardService quote, HyperlinkProtocolCapacityService capacity) {
        return new HyperlinkTaskActionService(store, quote,
                mock(HyperlinkProvisionFactService.class), mock(HyperlinkTaskRoundMapper.class),
                mock(HyperlinkCleanupStartService.class), mock(HyperlinkTaskAuditPort.class),
                new HyperlinkShortLinkGuard(""), capacity);
    }

    private HyperlinkProtocolCapacityService rejectingCapacity() {
        HyperlinkProtocolCapacityService capacity = mock(HyperlinkProtocolCapacityService.class);
        doThrow(new BusinessException(ErrorCode.HYPERLINK_PROTOCOL_CAPACITY_INSUFFICIENT))
                .when(capacity).requireSufficient(anyInt());
        return capacity;
    }

    private HyperlinkTaskConfigurationFactory.Normalized normalized(boolean enabled,
            int maxExecutingAccounts) {
        return new HyperlinkTaskConfigurationFactory.Normalized(
                HyperlinkTaskMode.INSTANT, HyperlinkTaskStartMode.NOW,
                mock(HyperlinkMessageContent.class), mock(HyperlinkAccountFilterDTO.class),
                null, 0, 500, 700, maxExecutingAccounts, maxExecutingAccounts,
                0, 0, enabled, false);
    }

    private HyperlinkTask task(int concurrentNum) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setCreatedBy(8L);
        task.setVersion(3);
        task.setConcurrentNum(concurrentNum);
        task.setShortLinkEnabled(false);
        return task;
    }

    private HyperlinkTaskRuntime runtime(boolean enabled) {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setEnabled(enabled);
        runtime.setRunStatus(0);
        runtime.setProvisionStatus(0);
        return runtime;
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, 7L, "u", "U", "t", "T", List.of(), List.of());
    }

    private void assertCapacityFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(
                                ErrorCode.HYPERLINK_PROTOCOL_CAPACITY_INSUFFICIENT.code()));
    }
}
