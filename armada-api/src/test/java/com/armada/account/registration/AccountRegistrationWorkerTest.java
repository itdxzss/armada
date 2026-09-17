package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.service.AccountImportService;
import com.armada.account.service.AccountService;
import com.armada.account.service.impl.AccountRegistrationImportService;
import com.armada.account.service.impl.AccountRegistrationLease;
import com.armada.account.service.impl.AccountRegistrationServiceImpl;
import com.armada.account.service.impl.AccountRegistrationStore;
import com.armada.account.service.impl.AccountRegistrationWorker;
import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationSnapshot;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationState;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 工作流单测只mock外部服务；SQL/租约隔离另由真实H2测试证明。 */
class AccountRegistrationWorkerTest {
    private final AccountRegistrationMapper mapper = mock(AccountRegistrationMapper.class);
    private final AccountRegistrationLease lease = mock(AccountRegistrationLease.class);
    private final AccountRegistrationServiceImpl service = mock(AccountRegistrationServiceImpl.class);
    private final AccountRegistrationStore store = mock(AccountRegistrationStore.class);
    private final GrizzlySmsClient grizzly = mock(GrizzlySmsClient.class);
    private final CobaltRegistrationClient cobalt = mock(CobaltRegistrationClient.class);
    private final AccountRegistrationImportService importer = mock(AccountRegistrationImportService.class);
    private final AccountImportService imports = mock(AccountImportService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final AccountRegistrationWorker worker = new AccountRegistrationWorker(mapper, lease, service,
            store, grizzly, cobalt, importer, imports, accounts);
    private final AccountRegistrationItem item = new AccountRegistrationItem();
    private final AccountRegistrationTask task = new AccountRegistrationTask();
    private final List<Integer> savedStates = new ArrayList<>();

    @BeforeEach void setUp() {
        item.setId(10L); item.setTenantId(7L); item.setTaskId(4L); item.setState(1);
        item.setStartedAt(System.currentTimeMillis()); item.setRegistrationId("reg_7_10");
        item.setPhoneNumber("12025550123"); item.setActivationId("1001"); item.setAccountId(71L);
        task.setId(4L); task.setServiceCode("wa"); task.setCountryId("12");
        task.setUnitPrice(new BigDecimal("1.35")); task.setAccountType(1); task.setCancelRequested(false);
        when(mapper.nextWork(anyLong())).thenReturn(item); when(mapper.findItem(10L)).thenReturn(item);
        when(mapper.findTask(4L)).thenReturn(task); when(mapper.claim(any(), anyLong())).thenReturn(1);
        when(lease.acquire()).thenReturn("lease"); when(service.orderingDisabledReason()).thenReturn("");
        when(grizzly.getPriceTiers("wa", "12")).thenReturn(List.of(tier("1.35", "20")));
        when(mapper.updateClaimed(any())).thenAnswer(invocation -> {
            AccountRegistrationItem saved = invocation.getArgument(0); savedStates.add(saved.getState()); return 1;
        });
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void deviceNeverCallsCobaltOrImportsWhileWaitingForNativeResult() {
        task.setExecutionMode(2); item.setState(3);
        worker.tick();
        assertThat(item.getState()).isEqualTo(3);
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }

    @Test void expiredDevicePermitCannotPurchase() {
        task.setExecutionMode(2); task.setPurchaseBefore(1L);
        worker.tick();
        assertThat(item.getState()).isEqualTo(8);
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test void invalidDeviceImportStateFailsClosed() {
        task.setExecutionMode(2); item.setState(5);
        worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }

    @Test void devicePurchasesOneNumberWithoutCobaltCapability() {
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE);
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        when(grizzly.acquireNumber(any())).thenReturn(activation("1.35"));
        worker.tick(); worker.tick();
        assertThat(item.getState()).isEqualTo(3);
        verify(grizzly, org.mockito.Mockito.times(1)).acquireNumber(any());
        verify(service, never()).orderingDisabledReason();
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }
    @Test void pinnedMerchantIgnoresUnrelatedCatalogAndIsNeverReplacedAfterNoNumbers() {
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE); task.setProviderId("222");
        task.setCountryId("187"); task.setUnitPrice(new BigDecimal("0.88"));
        item.setPhoneNumber(null); item.setActivationId(null);
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        when(grizzly.acquireNumber(any())).thenAnswer(invocation -> {
            var request = (com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest) invocation.getArgument(0);
            assertThat(request.country()).isEqualTo("187");
            assertThat(request.maxPrice()).isEqualByComparingTo("0.88");
            assertThat(request.options().providerIds()).containsExactly("222");
            assertThat(request.options().minPrice()).isNull();
            throw new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false);
        });
        worker.tick(); worker.tick();
        assertThat(item.getState()).isEqualTo(1); assertThat(item.getFailureCode()).isEqualTo("SMS_NO_NUMBERS_RETRY");
        verify(grizzly, org.mockito.Mockito.times(1)).acquireNumber(any());
        verify(grizzly, never()).getPriceTiers(anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }
    @Test void noNumbersRetriesAtMostFiftyTimesWithFiveSecondDelay() {
        task.setProviderId("202");
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false));
        for (int attempt = 1; attempt <= 50; attempt++) {
            item.setNextPurchaseAt(null);
            long before = System.currentTimeMillis();
            worker.tick();
            assertThat(item.getPurchaseAttempts()).isEqualTo(attempt);
            if (attempt < 50) {
                assertThat(item.getState()).isEqualTo(1);
                assertThat(item.getNextPurchaseAt()).isBetween(before + 5000, System.currentTimeMillis() + 5000);
                worker.tick();
            }
            verify(grizzly, org.mockito.Mockito.times(attempt)).acquireNumber(any());
        }
        assertThat(item.getState()).isEqualTo(8);
        assertThat(item.getFailureCode()).isEqualTo("SMS_NO_NUMBERS_EXHAUSTED");
        worker.tick();
        verify(grizzly, org.mockito.Mockito.times(50)).acquireNumber(any());
    }

    @Test void retrySuccessStopsFurtherPurchases() {
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE); task.setProviderId("202");
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false))
                .thenReturn(activation("1.35"));
        worker.tick(); item.setNextPurchaseAt(null); worker.tick(); worker.tick();
        assertThat(item.getState()).isEqualTo(3);
        assertThat(item.getPurchaseAttempts()).isEqualTo(2);
        verify(grizzly, org.mockito.Mockito.times(2)).acquireNumber(any());
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer);
    }

    @Test void cancellationWhileWaitingDoesNotPurchaseAgain() {
        task.setProviderId("202");
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false));
        worker.tick(); task.setCancelRequested(true); item.setNextPurchaseAt(null); worker.tick();
        assertThat(item.getState()).isEqualTo(10);
        verify(grizzly).acquireNumber(any());
    }

    @Test void knownNonInventoryErrorDoesNotRetry() {
        task.setProviderId("202");
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_BALANCE, false));
        worker.tick(); worker.tick();
        assertThat(item.getState()).isEqualTo(8);
        verify(grizzly).acquireNumber(any());
    }

    @Test void expiredPermitDuringRetryStopsBeforeNextRequest() {
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE); task.setProviderId("202");
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false));
        worker.tick(); task.setPurchaseBefore(1L); item.setNextPurchaseAt(null); worker.tick();
        assertThat(item.getFailureCode()).isEqualTo("DEVICE_PERMIT_EXPIRED");
        verify(grizzly).acquireNumber(any());
    }

    @Test void pinnedMerchantStillRejectsUnexpectedActualPrice() {
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE); task.setProviderId("222");
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        when(grizzly.acquireNumber(any())).thenReturn(activation("0.88"));
        worker.tick();
        assertThat(item.getState()).isEqualTo(11); assertThat(item.getFailureCode()).isEqualTo("PURCHASE_PRICE_MISMATCH");
        verify(store).cancel(4L);
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }
    @Test void explicitMerchantProducesExactHttpQueryAndNoFallbackPurchase() {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.grizzlysms.com");
        var http = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var properties = new com.armada.platform.sms.grizzly.GrizzlySmsProperties();
        properties.setEnabled(true); properties.setPurchasesEnabled(true); properties.setApiKey("test-only-key");
        var client = new GrizzlySmsClient(builder.build(), new com.fasterxml.jackson.databind.ObjectMapper(), properties);
        http.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("action", "getNumberV2"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("country", "187"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("service", "wa"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("providerIds", "222"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("maxPrice", "0.88"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("minPrice", "exceptProviderIds"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "NO_NUMBERS", org.springframework.http.MediaType.TEXT_PLAIN));
        task.setExecutionMode(2); task.setPurchaseBefore(Long.MAX_VALUE); task.setProviderId("222");
        task.setCountryId("187"); task.setUnitPrice(new BigDecimal("0.88"));
        when(service.deviceOrderingDisabledReason()).thenReturn("");
        var pinned = new AccountRegistrationWorker(mapper, lease, service, store, client, cobalt, importer, imports, accounts);
        pinned.tick(); pinned.tick(); http.verify();
        assertThat(item.getState()).isEqualTo(1); assertThat(item.getFailureCode()).isEqualTo("SMS_NO_NUMBERS_RETRY");
        org.mockito.Mockito.verifyNoInteractions(cobalt, importer, imports, accounts);
    }

    @Test void purchasePersistsIntentBeforeHttpUsesExactPriceAndRestoresTenant() {
        TenantContext.set(99L);
        when(grizzly.acquireNumber(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            assertThat(savedStates).containsExactly(2);
            var request = (com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest) invocation.getArgument(0);
            assertThat(request.maxPrice()).isEqualByComparingTo("1.35");
            assertThat(request.options().minPrice()).isEqualByComparingTo("1.35");
            assertThat(request.options().providerIds()).isEmpty();
            return activation("1.35");
        });
        worker.tick();
        assertThat(savedStates).containsExactly(2, 3);
        assertThat(item.getActivationId()).isEqualTo("9001");
        assertThat(TenantContext.get()).isEqualTo(99L);
        verify(lease).release("lease");
    }

    @Test void timeoutIsUnknownStopsFutureItemsAndNeverBuysAgain() {
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.TRANSPORT_ERROR, true));
        worker.tick();
        assertThat(item.getState()).isEqualTo(9); verify(store).cancel(4L);
        worker.tick();
        verify(grizzly).acquireNumber(any());
    }

    @Test void crashedPurchasingStateNeverRepeatsPurchase() {
        item.setState(2); worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test void overpricePreservesActivationAndActualCostBeforeStopping() {
        when(grizzly.acquireNumber(any())).thenReturn(activation("5.00")); worker.tick();
        assertThat(item.getState()).isEqualTo(11);
        assertThat(item.getActivationId()).isEqualTo("9001");
        assertThat(item.getActualCost()).isEqualByComparingTo("5.00");
        verify(store).cancel(4L);
    }

    @Test void automaticSelectionLeavesMerchantChoiceToSupplier() {
        when(grizzly.getPriceTiers("wa", "12")).thenReturn(List.of(
                tier("0.15", "10"), tier("1.35", "10", "20")));
        when(grizzly.acquireNumber(any())).thenAnswer(invocation -> {
            var request = (com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest) invocation.getArgument(0);
            assertThat(request.options().minPrice()).isEqualByComparingTo("1.35");
            assertThat(request.options().providerIds()).isEmpty();
            assertThat(request.maxPrice()).isEqualByComparingTo("1.35");
            return activation("1.35");
        });
        worker.tick();
        assertThat(item.getState()).isEqualTo(3);
    }

    @Test void automaticSelectionDoesNotDependOnMerchantCatalogDuringPurchase() {
        when(grizzly.getPriceTiers("wa", "12")).thenThrow(new IllegalStateException("catalog unavailable"));
        when(grizzly.acquireNumber(any())).thenReturn(activation("1.35"));
        worker.tick();
        assertThat(item.getState()).isEqualTo(3);
        verify(grizzly, never()).getPriceTiers(anyString(), anyString());
    }

    @Test void cheaperUnexpectedAllocationIsCancelledWithoutStartingRegistration() {
        when(grizzly.acquireNumber(any())).thenReturn(activation("0.15"));
        worker.tick();
        assertThat(item.getState()).isEqualTo(11);
        assertThat(item.getActualCost()).isEqualByComparingTo("0.15");
        assertThat(item.getFailureCode()).isEqualTo("PURCHASE_PRICE_MISMATCH");
        verify(store).cancel(4L);
        verify(cobalt, never()).create(anyString(), anyString(), anyInt());
    }

    @Test void priceMismatchCancellationChecksExistingOrderAndNeverRepurchases() {
        item.setState(11); item.setFailureCode("PURCHASE_PRICE_MISMATCH");
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_CODE,
                Optional.empty(), Optional.empty()));
        worker.tick();
        assertThat(item.getState()).isEqualTo(8);
        assertThat(item.getFailureCode()).isEqualTo("PURCHASE_PRICE_MISMATCH_CANCELLED");
        verify(grizzly).setStatus("1001", GrizzlyStatusUpdate.CANCEL);
        verify(grizzly, never()).acquireNumber(any());
        verify(cobalt, never()).status(anyString());
    }

    @Test void cancellationWaitIsPersistedFromRelativeVendorWindow() {
        var empty = Optional.<String>empty();
        var details = new GrizzlyActivation.Details(Optional.of("12"), Optional.of("2026-09-15 03:53:24"),
                empty, Optional.of("2026-09-15 03:58:24"), empty);
        when(grizzly.acquireNumber(any())).thenReturn(new GrizzlyActivation("9001", "12025550123",
                new BigDecimal("0.15"), 643, details));
        long before = System.currentTimeMillis();
        worker.tick();
        assertThat(item.getCancelAfter()).isBetween(before + 305_000, System.currentTimeMillis() + 305_000);
        worker.tick();
        assertThat(item.getState()).isEqualTo(11);
        verify(grizzly, never()).getStatus(anyString());
    }

    @Test void alreadyCancelledOrderIsSettledWithoutRepeatingCancellation() {
        item.setState(11);
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.CANCELLED,
                Optional.empty(), Optional.empty()));
        worker.tick();
        assertThat(item.getState()).isEqualTo(8);
        verify(grizzly, never()).setStatus(anyString(), any());
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test void uncertainCancellationRetainsActivationForReviewAndNeverRetriesPurchase() {
        item.setState(11);
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_CODE,
                Optional.empty(), Optional.empty()));
        org.mockito.Mockito.doThrow(new GrizzlySmsException(GrizzlySmsFailure.TRANSPORT_ERROR, true))
                .when(grizzly).setStatus("1001", GrizzlyStatusUpdate.CANCEL);
        worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        assertThat(item.getActivationId()).isEqualTo("1001");
        assertThat(item.getFailureCode()).isEqualTo("PURCHASE_CANCELLATION_UNCONFIRMED");
        worker.tick();
        verify(grizzly).setStatus("1001", GrizzlyStatusUpdate.CANCEL);
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test void receivedSmsOnMispricedOrderRequiresReviewInsteadOfRegistrationOrCancellation() {
        item.setState(11);
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED,
                Optional.of("001234"), Optional.empty()));
        worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        verify(grizzly, never()).setStatus(anyString(), any());
        verify(cobalt, never()).status(anyString());
    }

    @Test void expiredCancellationDoesNotBlockOtherWorkIndefinitely() {
        item.setState(11);
        item.setStartedAt(System.currentTimeMillis() - java.time.Duration.ofMinutes(26).toMillis());
        worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        assertThat(item.getFailureCode()).isEqualTo("PURCHASE_CANCELLATION_TIMEOUT");
        verify(grizzly, never()).setStatus(anyString(), any());
    }

    @Test void automaticPurchaseSendsExactBoundsWithoutAnyMerchantFilters() {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.grizzlysms.com");
        var http = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var properties = new com.armada.platform.sms.grizzly.GrizzlySmsProperties();
        properties.setEnabled(true); properties.setPurchasesEnabled(true); properties.setApiKey("test-only-key");
        var client = new GrizzlySmsClient(builder.build(), new com.fasterxml.jackson.databind.ObjectMapper(), properties);
        http.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("action", "getNumberV2"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("country", "12"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("service", "wa"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("minPrice", "0.150000000000"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("maxPrice", "0.150000000000"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("providerIds", "exceptProviderIds"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"activationId\":\"9001\",\"phoneNumber\":\"12025550123\",\"activationCost\":0.15,\"currency\":643}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        task.setUnitPrice(new BigDecimal("0.150000000000"));
        new AccountRegistrationWorker(mapper, lease, service, store, client, cobalt, importer, imports, accounts).tick();
        http.verify();
        assertThat(item.getState()).isEqualTo(3);
        assertThat(item.getActualCost()).isEqualByComparingTo("0.15");
        assertThat(item.getCurrency()).isEqualTo(643);
        verify(cobalt, never()).create(anyString(), anyString(), anyInt());
    }

    @Test void disabledCapabilityMakesNoPurchaseAndDoesNotConsumeAttempt() {
        when(service.orderingDisabledReason()).thenReturn("COBALT_DISABLED"); worker.tick();
        assertThat(item.getState()).isEqualTo(1);
        verify(grizzly, never()).acquireNumber(any()); assertThat(savedStates).isEmpty();
    }

    @Test void lostLeaseBeforePurchasingMakesNoHttpCall() {
        org.mockito.Mockito.doReturn(0).when(mapper).updateClaimed(any()); worker.tick();
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test void previousSmsCodeIsNeverSubmitted() {
        item.setState(3); when(cobalt.status(anyString())).thenReturn(snapshot(CobaltRegistrationState.WAITING_CODE));
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_RETRY,
                Optional.empty(), Optional.of("001234")));
        worker.tick(); verify(cobalt, never()).submitCode(anyString(), anyString());
    }

    @Test void codeSubmissionPersistsRegisteringFirstAndPreservesLeadingZero() {
        item.setState(3); when(cobalt.status(anyString())).thenReturn(snapshot(CobaltRegistrationState.WAITING_CODE));
        when(grizzly.getStatus("1001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED,
                Optional.of("001234"), Optional.empty()));
        when(cobalt.submitCode("reg_7_10", "001234")).thenAnswer(invocation -> {
            assertThat(savedStates).containsExactly(4); return snapshot(CobaltRegistrationState.VERIFYING);
        });
        worker.tick(); assertThat(item.getState()).isEqualTo(4);
        verify(cobalt).submitCode("reg_7_10", "001234");
    }

    @Test void registrationSuccessIsNotAccountOnlineSuccess() {
        item.setState(4); when(cobalt.status(anyString())).thenReturn(snapshot(CobaltRegistrationState.REGISTERED));
        worker.tick(); assertThat(item.getState()).isEqualTo(5);
        verify(importer, never()).importOne(any(), any(), any());
    }

    @Test void actualAndroidOnlineIsRequiredForSuccess() {
        item.setState(6); when(accounts.getLoginStatesByIds(List.of(71L))).thenReturn(Map.of(71L, 1));
        worker.tick(); assertThat(item.getState()).isEqualTo(7);
    }

    @Test void expiredCredentialImportDoesNotBlockTheQueueForever() {
        item.setState(5);
        item.setStartedAt(System.currentTimeMillis() - java.time.Duration.ofMinutes(31).toMillis());
        worker.tick();
        assertThat(item.getState()).isEqualTo(9);
        assertThat(item.getFailureCode()).isEqualTo("ACCOUNT_IMPORT_TIMEOUT");
        verify(cobalt, never()).exportSix(anyString());
        verify(importer, never()).importOne(any(), any(), any());
    }

    private GrizzlyActivation activation(String cost) {
        var empty = Optional.<String>empty();
        return new GrizzlyActivation("9001", "12025550123", new BigDecimal(cost), 643,
                new GrizzlyActivation.Details(empty, empty, empty, empty, empty));
    }
    private GrizzlyPriceTier tier(String cost, String... providers) {
        return new GrizzlyPriceTier("12", "wa", new BigDecimal(cost), 100, List.of(providers));
    }
    private CobaltRegistrationSnapshot snapshot(CobaltRegistrationState state) {
        return new CobaltRegistrationSnapshot("reg_7_10", "12025550123", state, Optional.empty());
    }
}
