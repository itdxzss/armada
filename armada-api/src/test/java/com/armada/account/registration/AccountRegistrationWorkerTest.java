package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
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
        when(mapper.nextWork()).thenReturn(item); when(mapper.findItem(10L)).thenReturn(item);
        when(mapper.findTask(4L)).thenReturn(task); when(mapper.claim(any(), anyLong())).thenReturn(1);
        when(lease.acquire()).thenReturn("lease"); when(service.orderingDisabledReason()).thenReturn("");
        when(mapper.updateClaimed(any())).thenAnswer(invocation -> {
            AccountRegistrationItem saved = invocation.getArgument(0); savedStates.add(saved.getState()); return 1;
        });
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void purchasePersistsIntentBeforeHttpUsesExactPriceAndRestoresTenant() {
        TenantContext.set(99L);
        when(grizzly.acquireNumber(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            assertThat(savedStates).containsExactly(2);
            var request = (com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest) invocation.getArgument(0);
            assertThat(request.maxPrice()).isEqualByComparingTo("1.35");
            assertThat(request.options().minPrice()).isEqualByComparingTo("1.35");
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
        assertThat(item.getState()).isEqualTo(9);
        assertThat(item.getActivationId()).isEqualTo("9001");
        assertThat(item.getActualCost()).isEqualByComparingTo("5.00");
        verify(store).cancel(4L);
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

    private GrizzlyActivation activation(String cost) {
        var empty = Optional.<String>empty();
        return new GrizzlyActivation("9001", "12025550123", new BigDecimal(cost), 643,
                new GrizzlyActivation.Details(empty, empty, empty, empty, empty));
    }
    private CobaltRegistrationSnapshot snapshot(CobaltRegistrationState state) {
        return new CobaltRegistrationSnapshot("reg_7_10", "12025550123", state, Optional.empty());
    }
}
