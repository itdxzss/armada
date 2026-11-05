package com.armada.account.smsactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.SmsActivationConverter;
import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.model.dto.SmsActivationCreateDTO;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.vo.AccountRegistrationCatalogVO;
import com.armada.account.service.AccountRegistrationService;
import com.armada.account.service.SmsRegistrationService;
import com.armada.account.service.impl.SmsActivationServiceImpl;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;

/** 手动接码流程单测：只 mock 平台与 Mapper，SQL 与租户隔离由 H2 测试证明。 */
class SmsActivationServiceTest {
    private final SmsActivationMapper mapper = mock(SmsActivationMapper.class);
    private final AccountRegistrationService registrations = mock(AccountRegistrationService.class);
    private final GrizzlySmsClient grizzly = mock(GrizzlySmsClient.class);
    private final GrizzlySmsProperties properties = new GrizzlySmsProperties();
    private final SmsRegistrationService registrationService = mock(SmsRegistrationService.class);
    private final SmsActivationServiceImpl service = new SmsActivationServiceImpl(mapper,
            Mappers.getMapper(SmsActivationConverter.class), registrations, grizzly, properties, registrationService);
    private final List<Integer> transitions = new ArrayList<>();
    private SmsActivation stored;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setPurchasesEnabled(true);
        // 办理状态附加与占用判断由 SmsRegistrationService 测试负责，这里让它透传、不占用。
        when(registrationService.disabledReason()).thenReturn("");
        when(registrationService.attach(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(registrations.catalog()).thenReturn(new AccountRegistrationCatalogVO("wa", "WhatsApp",
                List.of(new AccountRegistrationCatalogVO.Country("187", "美国")), false, "COBALT_DISABLED"));
        when(mapper.insert(any())).thenAnswer(invocation -> {
            SmsActivation row = invocation.getArgument(0);
            row.setId(1L);
            stored = row;
            return 1;
        });
        when(mapper.find(1L)).thenAnswer(invocation -> stored);
        when(mapper.transition(any(), anyInt())).thenAnswer(invocation -> {
            SmsActivation row = invocation.getArgument(0);
            transitions.add(row.getState());
            stored = row;
            return 1;
        });
    }

    @Test
    void purchaseSavesNumberWithProviderWindowsAndIgnoresCobaltGate() {
        when(grizzly.acquireNumber(any())).thenReturn(activation(Optional.of("2026-11-05 10:00:00"),
                Optional.of("2026-11-05 10:15:00"), Optional.of("2026-11-05 10:02:00")));
        long before = System.currentTimeMillis();

        var result = service.create(new SmsActivationCreateDTO("187", new BigDecimal("0.5"), "196"), 42L);

        var request = ArgumentCaptor.forClass(GrizzlyNumberRequest.class);
        verify(grizzly).acquireNumber(request.capture());
        assertThat(request.getValue().options().providerIds()).containsExactly("196");
        assertThat(request.getValue().options().minPrice()).isNull();
        assertThat(result.state()).isEqualTo("WAITING_CODE");
        assertThat(result.phoneNumber()).isEqualTo("12025550123");
        assertThat(stored.getCreatedBy()).isEqualTo(42L);
        assertThat(stored.getCancelAfter() - before).isBetween(120_000L, 125_000L);
        assertThat(stored.getExpiresAt() - before).isBetween(900_000L, 905_000L);
    }

    @Test
    void missingProviderTimesFallBackToImmediateCancelAndDefaultLifetime() {
        when(grizzly.acquireNumber(any())).thenReturn(activation(Optional.empty(), Optional.empty(), Optional.empty()));
        long before = System.currentTimeMillis();
        service.create(new SmsActivationCreateDTO("187", BigDecimal.ONE, null), 1L);
        assertThat(stored.getCancelAfter() - before).isBetween(0L, 5_000L);
        assertThat(stored.getExpiresAt() - before).isBetween(1_200_000L, 1_205_000L);
    }

    @Test
    void noNumbersAndUnknownOutcomeDoNotSaveOrRetry() {
        when(grizzly.acquireNumber(any())).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.NO_NUMBERS, false));
        assertThatThrownBy(() -> service.create(new SmsActivationCreateDTO("187", BigDecimal.ONE, null), 1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("暂无号码");

        var unknown = new GrizzlySmsException(GrizzlySmsFailure.TRANSPORT_ERROR, true);
        doThrow(unknown).when(grizzly).acquireNumber(any());
        assertThatThrownBy(() -> service.create(new SmsActivationCreateDTO("187", BigDecimal.ONE, null), 1L))
                .isSameAs(unknown);

        verify(grizzly, org.mockito.Mockito.times(2)).acquireNumber(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void disabledPurchaseOrUnknownCountryNeverCallsProvider() {
        properties.setPurchasesEnabled(false);
        assertThatThrownBy(() -> service.create(new SmsActivationCreateDTO("187", BigDecimal.ONE, null), 1L))
                .hasMessageContaining("GRIZZLY_PURCHASE_DISABLED");
        properties.setPurchasesEnabled(true);
        assertThatThrownBy(() -> service.create(new SmsActivationCreateDTO("999", BigDecimal.ONE, null), 1L))
                .hasMessageContaining("美国目录");
        assertThatThrownBy(() -> service.create(new SmsActivationCreateDTO("187", BigDecimal.ZERO, null), 1L))
                .hasMessageContaining("参数无效");
        verify(grizzly, never()).acquireNumber(any());
    }

    @Test
    void refreshWritesNewCodeButNotPreviousCode() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        when(grizzly.getStatus("9001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_RETRY,
                Optional.empty(), Optional.of("111111")));
        assertThat(service.refresh(1L).smsCode()).isNull();
        assertThat(transitions).isEmpty();

        when(grizzly.getStatus("9001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED,
                Optional.of("004271"), Optional.empty()));
        var result = service.refresh(1L);
        assertThat(result.state()).isEqualTo("RECEIVED");
        assertThat(result.smsCode()).isEqualTo("004271");
        assertThat(result.receivedAt()).isNotNull();

        service.refresh(1L);
        verify(grizzly, org.mockito.Mockito.times(2)).getStatus("9001");
    }

    @Test
    void refreshMarksProviderCancelAndLocalExpiry() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        when(grizzly.getStatus("9001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.CANCELLED,
                Optional.empty(), Optional.empty()));
        assertThat(service.refresh(1L).state()).isEqualTo("CANCELLED");

        stored = waiting(System.currentTimeMillis() - 1);
        assertThat(service.refresh(1L).state()).isEqualTo("EXPIRED");
        verify(grizzly, org.mockito.Mockito.times(1)).getStatus(anyString());
    }

    @Test
    void refreshKeepsStateWhenProviderQueryFails() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        when(grizzly.getStatus("9001")).thenThrow(new GrizzlySmsException(GrizzlySmsFailure.HTTP_ERROR, false));
        assertThat(service.refresh(1L).state()).isEqualTo("WAITING_CODE");
        assertThat(transitions).isEmpty();
    }

    @Test
    void cancelRespectsWindowAndStateAndKeepsStateOnProviderFailure() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        stored.setCancelAfter(System.currentTimeMillis() + 30_000);
        assertThatThrownBy(() -> service.cancel(1L)).hasMessageContaining("秒才能取消");

        stored.setCancelAfter(System.currentTimeMillis() - 1);
        doThrow(new GrizzlySmsException(GrizzlySmsFailure.BAD_STATUS, false))
                .when(grizzly).setStatus("9001", GrizzlyStatusUpdate.CANCEL);
        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(GrizzlySmsException.class);
        assertThat(transitions).isEmpty();

        org.mockito.Mockito.reset(grizzly);
        assertThat(service.cancel(1L).state()).isEqualTo("CANCELLED");
        verify(grizzly).setStatus("9001", GrizzlyStatusUpdate.CANCEL);

        assertThatThrownBy(() -> service.cancel(1L)).hasMessageContaining("等码中");
    }

    @Test
    void completeOnlyAfterCodeReceived() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        assertThatThrownBy(() -> service.complete(1L)).hasMessageContaining("已收到验证码");
        verify(grizzly, never()).setStatus(anyString(), any());

        stored.setState(SmsActivationState.RECEIVED.code());
        assertThat(service.complete(1L).state()).isEqualTo("COMPLETED");
        verify(grizzly).setStatus("9001", GrizzlyStatusUpdate.COMPLETE);
    }

    @Test
    void occupiedOrderRejectsManualCompleteAndCancelAndOnlyReadsLocallyOnRefresh() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        stored.setCancelAfter(0L);
        when(registrationService.isOccupied(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.cancel(1L)).hasMessageContaining("注册办理中");
        stored.setState(SmsActivationState.RECEIVED.code());
        assertThatThrownBy(() -> service.complete(1L)).hasMessageContaining("注册办理中");
        stored.setState(SmsActivationState.WAITING_CODE.code());
        assertThat(service.refresh(1L).state()).isEqualTo("WAITING_CODE");

        // 占用期间页面既不能改号商订单，也不能替后台任务去查码回写。
        verify(grizzly, never()).setStatus(anyString(), any());
        verify(grizzly, never()).getStatus(anyString());
        assertThat(transitions).isEmpty();
    }

    @Test
    void pollProviderIgnoresOccupationBecauseOnlyTheRegistrationWorkerCallsIt() {
        stored = waiting(System.currentTimeMillis() + 60_000);
        when(registrationService.isOccupied(1L)).thenReturn(true);
        when(grizzly.getStatus("9001")).thenReturn(new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED,
                Optional.of("004271"), Optional.empty()));

        var result = service.pollProvider(1L);

        assertThat(result.state()).isEqualTo("RECEIVED");
        assertThat(result.smsCode()).isEqualTo("004271");
    }

    @Test
    void unknownIdIsNotFound() {
        assertThatThrownBy(() -> service.refresh(2L)).isInstanceOf(BusinessException.class);
    }

    private static GrizzlyActivation activation(Optional<String> start, Optional<String> end, Optional<String> cancel) {
        return new GrizzlyActivation("9001", "12025550123", new BigDecimal("0.5"), 840,
                new GrizzlyActivation.Details(Optional.empty(), start, end, cancel, Optional.empty()));
    }

    private static SmsActivation waiting(long expiresAt) {
        var row = new SmsActivation();
        row.setId(1L);
        row.setActivationId("9001");
        row.setPhoneNumber("12025550123");
        row.setCountryId("187");
        row.setCost(new BigDecimal("0.5"));
        row.setCurrency(840);
        row.setState(SmsActivationState.WAITING_CODE.code());
        row.setCancelAfter(0L);
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(1L);
        row.setUpdatedAt(1L);
        return row;
    }
}
