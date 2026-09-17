package com.armada.account.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.mapper.DeviceRegistrationPermitMapper;
import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationSetupDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.service.impl.AccountRegistrationServiceImpl;
import com.armada.account.service.impl.DeviceRegistrationPermitServiceImpl;
import com.armada.account.service.impl.DeviceRegistrationPermitStore;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceRegistrationPermitServiceTest {
    private final DeviceRegistrationPermitMapper permits = mock(DeviceRegistrationPermitMapper.class);
    private final AccountRegistrationMapper tasks = mock(AccountRegistrationMapper.class);
    private final DeviceRegistrationPermitStore store = mock(DeviceRegistrationPermitStore.class);
    private final AccountRegistrationServiceImpl registration = mock(AccountRegistrationServiceImpl.class);
    private final DeviceRegistrationPermitServiceImpl service = new DeviceRegistrationPermitServiceImpl(permits, tasks, store, registration);
    private final String device = "10000000-0000-4000-8000-000000000001";
    private final String request = "10000000-0000-4000-8000-000000000002";
    private final DeviceRegistrationPermit permit = new DeviceRegistrationPermit(7, device, request, "187", new BigDecimal("0.88"), System.currentTimeMillis() + 600000, "196", null);
    @BeforeEach void setup() { TenantContext.set(7L); when(permits.find(device)).thenReturn(permit); }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void credentialCannotReadAnotherTenantAndStoredActualMerchantWinsAfterStart() {
        assertThatThrownBy(() -> service.current(new DeviceRegistrationIdentity(8, device))).isInstanceOf(BusinessException.class);
        var task = new AccountRegistrationTask(); task.setProviderId("62"); when(tasks.findByRequestId(request)).thenReturn(task);
        assertThat(service.current(new DeviceRegistrationIdentity(7, device)).providerId()).isEqualTo("62");
        verifyNoInteractions(store, registration);
    }
    @Test void selectionChangesOnlyMerchantAndNeverCreatesAnotherPermit() {
        var selected = service.select(permit, new DeviceRegistrationStartDTO(request, "62"));
        assertThat(selected.providerId()).isEqualTo("62"); assertThat(selected.requestId()).isEqualTo(request);
        assertThat(selected.unitPrice()).isEqualByComparingTo(permit.unitPrice());
        assertThatThrownBy(() -> service.select(permit, new DeviceRegistrationStartDTO("old", "62"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.select(permit, new DeviceRegistrationStartDTO(request, "196,62"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store);
    }
    @Test void optionsExposeOnlyTheAuthorizedPriceTier() {
        when(registration.priceTiers("187")).thenReturn(List.of(
                new GrizzlyPriceTier("187", "wa", new BigDecimal("0.88"), 20, List.of("196")),
                new GrizzlyPriceTier("187", "wa", new BigDecimal("1.6"), 20, List.of("311"))));
        assertThat(service.options(permit)).hasSize(1); assertThat(service.options(permit).get(0).providerIds()).containsExactly("196");
        verifyNoInteractions(store);
    }
    @Test void prepareChecksCurrentMerchantBeforePersistingAndDoesNotBuy() {
        when(registration.deviceServiceCode("187")).thenReturn("wa");
        when(store.prepare(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.prepare(new DeviceRegistrationSetupDTO(request, device, "187", permit.unitPrice(), "196", permit.expiresAt()));
        var order = inOrder(registration, store);
        order.verify(registration).deviceServiceCode("187");
        order.verify(registration).requireAvailableTier("wa", "187", permit.unitPrice(), 1, "196");
        order.verify(store).prepare(any()); verify(store, never()).createDevice(any(), any());
    }
    @Test void invalidPriceExpiryOrDeviceCannotCreateAPermit() {
        assertThatThrownBy(() -> service.prepare(new DeviceRegistrationSetupDTO(request, device, "187", permit.unitPrice(), "196", 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.prepare(new DeviceRegistrationSetupDTO(request, "bad", "187", permit.unitPrice(), "196", permit.expiresAt())))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, registration);
    }
}
