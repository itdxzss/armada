package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkBillingSagaService;
import com.armada.hyperlink.task.service.HyperlinkCleanupService;
import com.armada.hyperlink.task.service.HyperlinkExecutionFactCleanupService;
import com.armada.hyperlink.task.service.HyperlinkFirstRoundService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkProvisioningService;
import com.armada.hyperlink.task.service.HyperlinkQuoteTokenService;
import com.armada.hyperlink.task.service.HyperlinkRecipientClaimService;
import com.armada.hyperlink.task.service.HyperlinkRebuildProvisionService;
import com.armada.hyperlink.task.service.HyperlinkRecipientCleanupService;
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
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

/** STOP 与未开始编辑重建均只登记意图，recipient 由后台固定批次推进。 */
class HyperlinkTaskCleanupFlowTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void stopSwitchesStateAndRegistersCleanupWithoutLoopingRecipients() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkCleanupStartService cleanup = mock(HyperlinkCleanupStartService.class);
        HyperlinkTaskRuntime runtime = runtime(true, 1, 2);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        when(store.requireTask(11L)).thenReturn(task(11L, 1));
        when(store.requireRuntime(11L)).thenReturn(runtime);
        when(store.transition(eq(11L), eq(true), eq(1), eq(true), eq(4), eq(2), anyLong()))
                .thenReturn(true);
        when(store.receipt(11L)).thenReturn(receipt(11L, true));
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store,
                mock(HyperlinkTaskQuoteGuardService.class), mock(HyperlinkProvisionFactService.class),
                mock(HyperlinkTaskRoundMapper.class), cleanup, audit,
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        HyperlinkTaskMutationReceiptVO result = service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.STOP, 3, null), principal());

        assertThat(result.provisionStatus()).isEqualTo(HyperlinkProvisionStatus.NOT_REQUIRED);
        verify(store).incrementVersion(eq(11L), eq(3), anyLong());
        verify(cleanup, times(1)).begin(eq(11L), eq(true), anyLong());
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.action() == HyperlinkTaskAuditPort.Action.STOP
                        && event.eventId().equals("hyperlink-task:action:11:STOP:version:3")));
    }

    @Test
    void startAfterProvisionFailureResumesOriginalJobInsteadOfSchedulingRound() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProvisionFactService provision = mock(HyperlinkProvisionFactService.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        when(store.requireTask(11L)).thenReturn(task(11L, 1));
        HyperlinkTaskRuntime failedRuntime = runtime(true, 0, 3);
        failedRuntime.setFailureCode(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE.code());
        when(store.requireRuntime(11L)).thenReturn(failedRuntime);
        when(store.requireContent(11L)).thenReturn(supportedContent());
        when(store.resumeProvisioning(eq(11L), anyLong())).thenReturn(true);
        when(store.receipt(11L)).thenReturn(new HyperlinkTaskMutationReceiptVO(11L,
                HyperlinkProvisionStatus.PROCESSING, true, 0, 4, 1000L, null, null));
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store, quote,
                provision, rounds, mock(HyperlinkCleanupStartService.class),
                audit, mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        HyperlinkTaskMutationReceiptVO result = service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 3, "quote"), principal());

        assertThat(result.provisionStatus()).isEqualTo(HyperlinkProvisionStatus.PROCESSING);
        verify(store).resumeProvisioning(eq(11L), anyLong());
        verify(rounds, never()).scheduleNow(anyLong(), anyLong());
        verify(provision, never()).prepare(any(), any(), anyLong());
        verify(provision, never()).replaceFailedOwnedQuote(any(), any(), anyLong());
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.action() == HyperlinkTaskAuditPort.Action.START));
    }

    @Test
    void startAfterQuoteStaleFailureReplacesUncalledBillingQuoteAndResumesOwnedClaim() {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkProvisionFactService provision = mock(HyperlinkProvisionFactService.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTask task = task(11L, 1);
        HyperlinkTaskRuntime failedRuntime = runtime(true, 0, 3);
        failedRuntime.setFailureCode(ErrorCode.HYPERLINK_QUOTE_STALE.code());
        HyperlinkQuoteTokenService.QuoteClaims newClaims = mock(
                HyperlinkQuoteTokenService.QuoteClaims.class);
        when(store.requireTask(11L)).thenReturn(task);
        when(store.requireRuntime(11L)).thenReturn(failedRuntime);
        when(store.requireContent(11L)).thenReturn(supportedContent());
        when(quote.forStart(eq("new-quote"), eq(11L), eq(3), eq(task), any(), anyLong()))
                .thenReturn(newClaims);
        when(store.resumeProvisioning(eq(11L), anyLong())).thenReturn(true);
        when(store.receipt(11L)).thenReturn(new HyperlinkTaskMutationReceiptVO(11L,
                HyperlinkProvisionStatus.PROCESSING, true, 0, 4, 1000L, null, null));
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store, quote,
                provision, rounds, mock(HyperlinkCleanupStartService.class), audit,
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        HyperlinkTaskMutationReceiptVO result = service.action(11L,
                new HyperlinkTaskActionDTO(HyperlinkTaskAction.START, 3, "new-quote"), principal());

        assertThat(result.provisionStatus()).isEqualTo(HyperlinkProvisionStatus.PROCESSING);
        assertThat(task.getVersion()).isEqualTo(4);
        verify(provision).replaceFailedOwnedQuote(eq(task), eq(newClaims), anyLong());
        verify(store).resumeProvisioning(eq(11L), anyLong());
        verify(provision, never()).prepare(any(), any(), anyLong());
        verify(rounds, never()).scheduleNow(anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"PAUSE,1,3", "RESUME,3,1"})
    void pauseAndResumeWriteTheirOwnAuditEvent(String actionName, int currentStatus,
            int nextStatus) {
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTaskAction action = HyperlinkTaskAction.valueOf(actionName);
        when(store.requireTask(11L)).thenReturn(task(11L, 1));
        when(store.requireRuntime(11L)).thenReturn(runtime(true, currentStatus, 2));
        when(store.transition(eq(11L), eq(true), eq(currentStatus), eq(true), eq(nextStatus),
                eq(2), anyLong())).thenReturn(true);
        when(store.receipt(11L)).thenReturn(receipt(11L, true));
        HyperlinkTaskActionService service = new HyperlinkTaskActionService(store,
                mock(HyperlinkTaskQuoteGuardService.class), mock(HyperlinkProvisionFactService.class),
                mock(HyperlinkTaskRoundMapper.class), mock(HyperlinkCleanupStartService.class), audit,
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        service.action(11L, new HyperlinkTaskActionDTO(action, 3, null), principal());

        verify(audit).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.action().name().equals(actionName)
                        && event.eventId().equals("hyperlink-task:action:11:" + actionName
                        + ":version:3")));
    }

    @Test
    void provisionBillingFailureMarksOriginalJobFailedWithoutCreatingFirstRound() {
        HyperlinkRecipientClaimService claims = mock(HyperlinkRecipientClaimService.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkFirstRoundService firstRound = mock(HyperlinkFirstRoundService.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        when(claims.claimNext(11L))
                .thenReturn(new HyperlinkRecipientClaimService.ClaimBatchResult(true, 20, 20));
        doThrow(new BusinessException(
                ErrorCode.HYPERLINK_BILLING_UNAVAILABLE, "钱包暂不可用"))
                .when(billing).ensureProvisionReservation(11L);
        HyperlinkProvisioningService service = new HyperlinkProvisioningService(
                claims, billing, firstRound, runtimes);

        service.advance(11L);

        verify(runtimes).markProvisionFailed(eq(11L),
                eq(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE.code()), eq("钱包暂不可用"), anyLong());
        verify(firstRound, never()).createFirstRound(anyLong());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedQuoteStaleCleanupAbandonsLocalReserveBeforeReleasingClaim(
            boolean finalizeBilling) {
        HyperlinkTaskRecipientClaimMapper claims = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setClaimStatus(3);
        when(claims.selectByTaskId(7L, 11L)).thenReturn(claim);
        when(billing.abandonFailedStaleUncalledReservation(11L)).thenReturn(true);
        HyperlinkCleanupStartService service = new HyperlinkCleanupStartService(
                claims, rounds, recipients, billing);

        service.begin(11L, finalizeBilling, 1000L);

        InOrder order = inOrder(billing, claims);
        order.verify(billing).abandonFailedStaleUncalledReservation(11L);
        if (finalizeBilling) {
            order.verify(billing).beginFinalization(11L);
        }
        order.verify(claims).markReleasing(11L, 1000L);
        verify(billing, never()).abandonUnstartedReservation(anyLong());
    }

    @Test
    void ownedUnknownReserveRemainsPendingForIdempotentCleanupRecovery() {
        HyperlinkTaskRecipientClaimMapper claims = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setClaimStatus(3);
        when(claims.selectByTaskId(7L, 11L)).thenReturn(claim);
        when(billing.abandonFailedStaleUncalledReservation(11L)).thenReturn(false);
        HyperlinkCleanupStartService service = new HyperlinkCleanupStartService(
                claims, rounds, recipients, billing);

        service.begin(11L, false, 1000L);

        verify(billing).abandonFailedStaleUncalledReservation(11L);
        verify(billing, never()).abandonUnstartedReservation(anyLong());
        verify(claims).markReleasing(11L, 1000L);
    }

    @Test
    void stopCleanupAdvancesOnlyOneFiveHundredRowBatchPerInvocation() {
        TenantContext.set(7L);
        HyperlinkTaskRecipientClaimMapper claims = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setHyperlinkTaskId(11L);
        claim.setDataPackageId(21L);
        claim.setDataPackageGeneration(2);
        claim.setClaimStatus(4);
        when(claims.selectByTaskId(7L, 11L)).thenReturn(claim);
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(runtime(true, 4, 2));
        HyperlinkTaskRecipient stopRecipient = new HyperlinkTaskRecipient();
        stopRecipient.setId(31L);
        stopRecipient.setRecipientPhoneSnapshot("8613800000001");
        when(recipients.lockUnsubmittedForStop(7L, 11L, 21L, 2, 500))
                .thenReturn(List.of(stopRecipient));
        when(recipients.stopUnsubmittedByIds(eq(11L), eq(List.of(31L)), anyLong()))
                .thenReturn(1);
        when(data.releasePhones(eq(11L), eq(21L), eq(2),
                eq(List.of("8613800000001")), anyLong())).thenReturn(1);
        HyperlinkRecipientCleanupService service = new HyperlinkRecipientCleanupService(
                claims, recipients, runtimes, data);

        assertThat(service.cleanupBatch(11L)).isFalse();

        verify(recipients, times(1)).stopUnsubmittedByIds(
                eq(11L), eq(List.of(31L)), anyLong());
        verify(data, never()).releaseOwnedBatch(anyLong(), anyLong(), anyInt(), anyInt(), anyLong());
        verify(claims, never()).markReleased(anyLong(), anyLong());
    }

    @Test
    void stopCleanupDoesNotReleasePhonesWhenExactRecipientCasLoses() {
        HyperlinkTaskRecipientClaimMapper claims = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setHyperlinkTaskId(11L);
        claim.setDataPackageId(21L);
        claim.setDataPackageGeneration(2);
        claim.setClaimStatus(4);
        HyperlinkTaskRecipient candidate = new HyperlinkTaskRecipient();
        candidate.setId(31L);
        candidate.setRecipientPhoneSnapshot("8613800000001");
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(runtime(true, 4, 2));
        when(claims.selectByTaskId(7L, 11L)).thenReturn(claim);
        when(recipients.lockUnsubmittedForStop(7L, 11L, 21L, 2, 500))
                .thenReturn(List.of(candidate));
        when(recipients.stopUnsubmittedByIds(eq(11L), eq(List.of(31L)), anyLong()))
                .thenReturn(0);
        HyperlinkRecipientCleanupService service = new HyperlinkRecipientCleanupService(
                claims, recipients, runtimes, data);

        assertThatThrownBy(() -> service.cleanupBatch(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STOP recipient 集合发生并发变化");

        verify(data, never()).releasePhones(anyLong(), anyLong(), anyInt(), any(), anyLong());
    }

    @Test
    void stopCleanupRejectsPartialPhoneReleaseBeforeClaimCanBeReleased() {
        HyperlinkTaskRecipientClaimMapper claims = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setHyperlinkTaskId(11L);
        claim.setDataPackageId(21L);
        claim.setDataPackageGeneration(2);
        claim.setClaimStatus(4);
        HyperlinkTaskRecipient candidate = new HyperlinkTaskRecipient();
        candidate.setId(31L);
        candidate.setRecipientPhoneSnapshot("8613800000001");
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(runtime(true, 4, 2));
        when(claims.selectByTaskId(7L, 11L)).thenReturn(claim);
        when(recipients.lockUnsubmittedForStop(7L, 11L, 21L, 2, 500))
                .thenReturn(List.of(candidate));
        when(recipients.stopUnsubmittedByIds(eq(11L), eq(List.of(31L)), anyLong()))
                .thenReturn(1);
        when(data.releasePhones(eq(11L), eq(21L), eq(2),
                eq(List.of("8613800000001")), anyLong())).thenReturn(0);
        HyperlinkRecipientCleanupService service = new HyperlinkRecipientCleanupService(
                claims, recipients, runtimes, data);

        assertThatThrownBy(() -> service.cleanupBatch(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STOP recipient 与号码池释放数量不一致");

        verify(claims, never()).markReleased(anyLong(), anyLong());
    }

    @Test
    void stoppedTaskFinalizesBillingOnlyAfterRecipientCleanupCompletes() {
        HyperlinkRecipientCleanupService recipients = mock(HyperlinkRecipientCleanupService.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkExecutionFactCleanupService executions =
                mock(HyperlinkExecutionFactCleanupService.class);
        HyperlinkRebuildProvisionService rebuild = mock(HyperlinkRebuildProvisionService.class);
        when(recipients.cleanupBatch(11L)).thenReturn(true);
        when(runtimes.selectByTaskId(11L)).thenReturn(runtime(true, 4, 2));
        HyperlinkCleanupService service = new HyperlinkCleanupService(recipients, executions,
                billing, runtimes, rebuild);

        service.advance(11L);

        InOrder order = inOrder(billing, recipients);
        order.verify(billing).ensureCleanupSafe(11L);
        order.verify(recipients).cleanupBatch(11L);
        order.verify(billing).finalizeBilling(11L);
        verify(executions, never()).cleanup(anyLong());
        verify(rebuild, never()).rebuild(anyLong());
    }

    @Test
    void enabledTaskFrozenEditReturnsProcessingAndStartsRebuildSaga() {
        HyperlinkTaskConfigurationFactory factory = mock(HyperlinkTaskConfigurationFactory.class);
        HyperlinkTaskStoreService store = mock(HyperlinkTaskStoreService.class);
        HyperlinkTaskQuoteGuardService quote = mock(HyperlinkTaskQuoteGuardService.class);
        HyperlinkCleanupStartService cleanup = mock(HyperlinkCleanupStartService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkTaskSaveDTO request = mock(HyperlinkTaskSaveDTO.class);
        when(request.version()).thenReturn(3);
        when(request.sourceTaskId()).thenReturn(null);
        HyperlinkMessageContent content = mock(HyperlinkMessageContent.class);
        var normalized = new HyperlinkTaskConfigurationFactory.Normalized(
                HyperlinkTaskMode.INSTANT, HyperlinkTaskStartMode.NOW, content,
                mock(com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO.class),
                null, 0, 500, 700, 1, 1, 0, 0, true, false);
        when(factory.normalizeForUpdate(request, 3)).thenReturn(normalized);
        HyperlinkTask before = task(11L, 1);
        HyperlinkTask replacement = task(11L, 2);
        when(store.requireTask(11L)).thenReturn(before);
        HyperlinkTaskContent existingContent = new HyperlinkTaskContent();
        existingContent.setMessageType(3);
        when(store.requireContent(11L)).thenReturn(existingContent);
        when(store.requireRuntime(11L)).thenReturn(runtime(true, 0, 2));
        when(factory.task(eq(request), eq(normalized), anyLong(), anyLong())).thenReturn(replacement);
        when(factory.frozenScopeChanged(before, replacement)).thenReturn(true);
        when(factory.content(eq(11L), eq(content), anyLong())).thenReturn(new HyperlinkTaskContent());
        when(store.beginRebuild(eq(11L), eq(true), anyLong())).thenReturn(true);
        when(store.receipt(11L)).thenReturn(new HyperlinkTaskMutationReceiptVO(11L,
                HyperlinkProvisionStatus.PROCESSING, true, 0, 4, 1000L, null, null));
        HyperlinkTaskLifecycleService service = new HyperlinkTaskLifecycleService(factory, store,
                quote, mock(HyperlinkProvisionFactService.class), cleanup,
                mock(HyperlinkTaskRoundMapper.class), audit,
                mock(HyperlinkShortLinkGuard.class),
                mock(com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class));

        var result = service.update(11L, request, principal());

        assertThat(result.provisionStatus()).isEqualTo(HyperlinkProvisionStatus.PROCESSING);
        verify(cleanup).requireNoCommandedRecipient(11L);
        verify(cleanup).begin(eq(11L), eq(false), anyLong());
        verify(store).update(eq(replacement), any(HyperlinkTaskContent.class), eq(3));
        verify(quote).internalForSave(any(), any());
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.action() == HyperlinkTaskAuditPort.Action.UPDATE
                        && event.eventId().equals("hyperlink-task:update:11:version:4")));
    }

    private HyperlinkTask task(long id, int packageGeneration) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(id);
        task.setTenantId(7L);
        task.setCreatedBy(8L);
        task.setVersion(3);
        task.setDataPackageGeneration(packageGeneration);
        return task;
    }

    private HyperlinkTaskRuntime runtime(boolean enabled, int runStatus, int provisionStatus) {
        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setEnabled(enabled);
        runtime.setRunStatus(runStatus);
        runtime.setProvisionStatus(provisionStatus);
        return runtime;
    }

    private HyperlinkTaskContent supportedContent() {
        HyperlinkTaskContent content = new HyperlinkTaskContent();
        content.setMessageType(1);
        return content;
    }

    private HyperlinkTaskMutationReceiptVO receipt(long taskId, boolean enabled) {
        return new HyperlinkTaskMutationReceiptVO(taskId, HyperlinkProvisionStatus.READY,
                enabled, 4, 4, null, null, null);
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, 7L, "u", "U", "t", "T", List.of(), List.of());
    }
}
