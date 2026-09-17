package com.armada.account.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationResultDTO;
import com.armada.account.model.enums.DeviceRegistrationFailureKind;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.service.impl.*;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;

/** 原生注册的许可绑定、终点及 OTP 最小暴露行为。SQL另由H2验证。 */
class DeviceRegistrationServiceTest {
    private final AccountRegistrationMapper mapper = mock(AccountRegistrationMapper.class);
    private final DeviceRegistrationPermitStore store = mock(DeviceRegistrationPermitStore.class);
    private final AccountRegistrationServiceImpl registration = mock(AccountRegistrationServiceImpl.class);
    private final GrizzlySmsClient sms = mock(GrizzlySmsClient.class);
    private final DeviceRegistrationServiceImpl service = new DeviceRegistrationServiceImpl(mapper, store, registration, sms);
    private final DeviceRegistrationPermit permit = new DeviceRegistrationPermit(7, "phone", "request", "12", new BigDecimal("1.35"), Long.MAX_VALUE, null, null);
    private final AccountRegistrationTask task = new AccountRegistrationTask();
    private final AccountRegistrationItem item = new AccountRegistrationItem();
    @BeforeEach void setup() {
        TenantContext.set(7L);
        task.setId(4L); task.setExecutionMode(2); task.setDeviceId("phone"); task.setCountryId("12");
        task.setUnitPrice(permit.unitPrice()); task.setPurchaseBefore(permit.expiresAt());
        item.setId(10L); item.setTaskId(4L); item.setState(3); item.setPhoneNumber("12025550123");
        item.setActivationId("activation"); item.setStartedAt(System.currentTimeMillis()); item.setUpdatedAt(1L);
        when(mapper.findByRequestId("request")).thenReturn(task); when(mapper.listItems(4L)).thenReturn(List.of(item));
        when(mapper.claim(any(), anyLong())).thenReturn(1); when(mapper.updateClaimed(any())).thenReturn(1);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void absentTaskStatusDoesNotBuyOrCreate() {
        when(mapper.findByRequestId("request")).thenReturn(null);
        assertThat(service.status(permit).state()).isEqualTo("NOT_STARTED"); verifyNoInteractions(store, sms, registration);
    }
    @Test void repeatedStartReturnsSameTaskAndNeverCreatesAnotherPurchase() {
        item.setState(1);
        service.start(permit); service.start(permit);
        verifyNoInteractions(store, sms, registration);
    }
    @Test void foreignDeviceAndTenantCannotReadOrSubmit() {
        task.setDeviceId("other"); assertThatThrownBy(() -> service.status(permit)).isInstanceOf(BusinessException.class);
        TenantContext.set(8L); assertThatThrownBy(() -> service.result(permit, result("REGISTERED"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sms);
    }
    @Test void changedPermitAndCobaltCollisionAreRejected() {
        task.setUnitPrice(new BigDecimal("1.36"));
        assertThatThrownBy(() -> service.start(permit)).isInstanceOf(BusinessException.class);
        task.setUnitPrice(permit.unitPrice()); task.setExecutionMode(1);
        assertThatThrownBy(() -> service.status(permit)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sms, store);
    }
    @Test void pinnedMerchantIsVisibleAndCannotChangeForAnExistingTask() {
        var pinned = new DeviceRegistrationPermit(7, "phone", "request", "12", permit.unitPrice(), Long.MAX_VALUE, "222", null);
        when(mapper.findByRequestId("request")).thenReturn(null);
        assertThat(service.status(pinned).providerId()).isEqualTo("222");
        when(mapper.findByRequestId("request")).thenReturn(task);
        assertThatThrownBy(() -> service.start(pinned)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, sms);
    }
    @Test void replacementRequiresConfirmedSameDeviceFinishedTask() {
        var replacement = new DeviceRegistrationPermit(7, "phone", "next", "12", permit.unitPrice(), Long.MAX_VALUE, "222", "request");
        item.setState(8); item.setPhoneNumber(null); item.setActivationId(null);
        assertThat(service.status(replacement).replacesRequestId()).isEqualTo("request");
        for (int state : new int[]{1, 2, 3, 4, 11, 12}) {
            item.setState(state);
            assertThatThrownBy(() -> service.status(replacement)).isInstanceOf(BusinessException.class);
        }
        item.setState(9); item.setFailureCode("PURCHASE_RESULT_UNKNOWN");
        assertThatThrownBy(() -> service.status(replacement)).isInstanceOf(BusinessException.class);
        item.setFailureCode("REGISTRATION_TIMEOUT"); item.setActivationId("existing-order");
        item.setPhoneNumber("12025550123");
        assertThat(service.status(replacement).state()).isEqualTo("NOT_STARTED");
        item.setPhoneNumber(null); task.setDeviceId("another-device");
        assertThatThrownBy(() -> service.start(replacement)).isInstanceOf(BusinessException.class);
        task.setDeviceId("phone"); when(mapper.findByRequestId("request")).thenReturn(null);
        assertThat(service.status(replacement).state()).isEqualTo("NOT_STARTED");
        verifyNoInteractions(store, sms);
    }
    @Test void onlyReceivedCurrentCodeCanBeReturnedAndDuplicatePollIsThrottled() {
        when(sms.getStatus("activation")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED, Optional.of("123456"), Optional.empty()));
        var first = service.status(permit);
        assertThat(first.code()).isEqualTo("123456"); assertThat(first.state()).isEqualTo("REGISTERING");
        assertThat(first.toString()).doesNotContain("123456", item.getPhoneNumber());
        assertThat(service.status(permit).code()).isEmpty(); verify(sms, times(1)).getStatus("activation");
    }
    @Test void previousCodeIsNotSubmitted() {
        when(sms.getStatus("activation")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_RETRY, Optional.empty(), Optional.of("123456")));
        assertThat(service.status(permit).code()).isEmpty(); assertThat(item.getState()).isEqualTo(3);
    }
    @Test void badCodeStopsTaskInsteadOfGuessing() {
        when(sms.getStatus("activation")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED, Optional.of("unexpected"), Optional.empty()));
        assertThat(service.status(permit).code()).isEmpty(); assertThat(item.getState()).isEqualTo(9);
    }
    @Test void nativeSuccessTerminatesWithoutAccountImportAndReplaysIdempotently() {
        item.setState(4);
        assertThat(service.result(permit, result("REGISTERED")).state()).isEqualTo("DEVICE_REGISTERED");
        assertThat(service.result(permit, result("REGISTERED")).state()).isEqualTo("DEVICE_REGISTERED");
        verify(sms, times(1)).setStatus("activation", GrizzlyStatusUpdate.COMPLETE);
        verifyNoInteractions(store, registration);
        assertThat(item.getImportBatchId()).isNull(); assertThat(item.getAccountId()).isNull();
    }
    @Test void successBeforeReceivedCodeAndWrongPhoneCannotComplete() {
        assertThatThrownBy(() -> service.result(permit, result("REGISTERED"))).isInstanceOf(BusinessException.class);
        item.setState(4);
        assertThatThrownBy(() -> service.result(permit, new DeviceRegistrationResultDTO("request", "12025559999", "REGISTERED", null, null, null)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(sms);
    }
    @Test void failedClaimOrSaveNeverReleasesAnOtp() {
        when(mapper.claim(any(), anyLong())).thenReturn(0); assertThat(service.status(permit).code()).isEmpty();
        verifyNoInteractions(sms);
        when(mapper.claim(any(), anyLong())).thenReturn(1); when(mapper.updateClaimed(any())).thenReturn(0);
        when(sms.getStatus("activation")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED, Optional.of("123456"), Optional.empty()));
        assertThatThrownBy(() -> service.status(permit)).isInstanceOf(BusinessException.class);
    }
    @Test void stopPurchasedTaskPreservesOrderForReviewWithoutFalseRefund() {
        assertThat(service.result(permit, result("STOPPED")).state()).isEqualTo("UNKNOWN");
        assertThat(item.getActivationId()).isEqualTo("activation"); verifyNoInteractions(sms);
    }
    @Test void nativeFailurePersistsFirstReasonAndReplaysWithoutSupplierActions() {
        var failed = failure("blocked", DeviceRegistrationFailureKind.NUMBER, "目前无法登录");
        var first = service.result(permit, failed);
        assertThat(first.state()).isEqualTo("FAILED");
        assertThat(first.failureKind()).isEqualTo(DeviceRegistrationFailureKind.NUMBER);
        assertThat(first.failureCode()).isEqualTo("blocked");
        assertThat(first.failureDetail()).isEqualTo("目前无法登录");
        assertThat(first.phoneNumber()).isEqualTo(item.getPhoneNumber());
        var repeat = service.result(permit, failure("too_recent", DeviceRegistrationFailureKind.RATE_LIMIT, "later"));
        assertThat(repeat).isEqualTo(first);
        verify(mapper, times(1)).updateClaimed(item);
        verifyNoInteractions(sms, store, registration);
        assertThat(item.getActivationId()).isEqualTo("activation");
        assertThat(failed.toString()).doesNotContain("12025550123", "目前无法登录");
        assertThat(first.toString()).doesNotContain("12025550123", "目前无法登录");
    }
    @Test void failureIsAcceptedAfterCodeSubmissionButCannotOverwriteSuccess() {
        item.setState(4);
        assertThat(service.result(permit, failure("too_recent", DeviceRegistrationFailureKind.RATE_LIMIT, "")).state()).isEqualTo("FAILED");
        item.setState(12);
        assertThatThrownBy(() -> service.result(permit, failure("blocked", DeviceRegistrationFailureKind.NUMBER, "")))
                .isInstanceOf(BusinessException.class);
        assertThat(item.getState()).isEqualTo(12);
    }
    @Test void failureRejectsUnallocatedOtherTerminalAndNonDeviceRegistrationStates() {
        for (int state : new int[]{1, 2, 5, 6, 7, 8, 9, 10, 11, 12}) {
            item.setState(state);
            assertThatThrownBy(() -> service.result(permit, failure("blocked", DeviceRegistrationFailureKind.NUMBER, "")))
                    .isInstanceOf(BusinessException.class);
        }
        verify(mapper, never()).claim(any(), anyLong());
        verifyNoInteractions(sms, store, registration);
    }
    @Test void invalidFailureMetadataCannotClaimOrChangeTask() {
        for (var invalid : List.of(failure("", DeviceRegistrationFailureKind.NUMBER, ""),
                failure("a".repeat(65), DeviceRegistrationFailureKind.NUMBER, ""),
                failure("bad value", DeviceRegistrationFailureKind.UNKNOWN, ""),
                failure("blocked", null, ""), failure("blocked", DeviceRegistrationFailureKind.NUMBER, null),
                failure("blocked", DeviceRegistrationFailureKind.NUMBER, "x".repeat(257)),
                new DeviceRegistrationResultDTO("request", "", "FAILED", DeviceRegistrationFailureKind.UNKNOWN, "bad", ""),
                new DeviceRegistrationResultDTO("request", "12025550123", "REGISTERED", DeviceRegistrationFailureKind.UNKNOWN, "bad", ""))) {
            assertThatThrownBy(() -> service.result(permit, invalid)).isInstanceOf(BusinessException.class);
        }
        verify(mapper, never()).claim(any(), anyLong());
    }
    @Test void failedClaimOrWriteDoesNotAcknowledgeFailure() {
        var failed = failure("blocked", DeviceRegistrationFailureKind.NUMBER, "");
        when(mapper.claim(any(), anyLong())).thenReturn(0);
        assertThatThrownBy(() -> service.result(permit, failed)).isInstanceOf(BusinessException.class);
        assertThat(item.getState()).isEqualTo(3);
        when(mapper.claim(any(), anyLong())).thenReturn(1); when(mapper.updateClaimed(any())).thenReturn(0);
        assertThatThrownBy(() -> service.result(permit, failed)).isInstanceOf(BusinessException.class);
        verify(mapper).release(eq(item.getId()), anyString());
        verifyNoInteractions(sms);
    }
    private DeviceRegistrationResultDTO failure(String code, DeviceRegistrationFailureKind kind, String detail) {
        return new DeviceRegistrationResultDTO("request", "12025550123", "FAILED", kind, code, detail);
    }
    private DeviceRegistrationResultDTO result(String outcome) { return new DeviceRegistrationResultDTO("request", "12025550123", outcome, null, null, null); }
}
