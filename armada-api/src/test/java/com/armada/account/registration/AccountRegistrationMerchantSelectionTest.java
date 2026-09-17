package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.armada.account.model.dto.AccountRegistrationCreateDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.armada.account.service.impl.AccountRegistrationServiceImpl;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import java.math.BigDecimal;
import java.util.List;

/** 控端选择的商家必须作为任务参数进入后端，不能被丢弃。 */
class AccountRegistrationMerchantSelectionTest {
    @Test void controlRequestCarriesSelectedMerchant() throws Exception {
        var request = new ObjectMapper().readValue("""
            {"requestId":"10000000-0000-4000-8000-000000000009","countryId":"187",
             "unitPrice":0.88,"quantity":1,"accountGroupId":1,"accountType":1,
             "ipAllocationMode":"AUTO","ipRegion":"US","providerId":"196"}
            """, AccountRegistrationCreateDTO.class);
        assertThat(request).extracting("providerId").isEqualTo("196");
    }
    @Test void staleHistoricalMerchantIsRejectedBeforeAnyPurchase() {
        var sms = mock(GrizzlySmsClient.class);
        var service = new AccountRegistrationServiceImpl(null, null, null, sms, null, null, false, null);
        when(sms.getPriceTiers("wa", "187")).thenReturn(List.of(new GrizzlyPriceTier("187", "wa", new BigDecimal("0.88"), 20, List.of("196", "62"))));
        service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, "196");
        service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, null);
        assertThatThrownBy(() -> service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, "222"))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
        assertThatThrownBy(() -> service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, "196,62"))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
        verify(sms, never()).acquireNumber(any());
    }
    @Test void automaticModeNeedsPriceInventoryButNotMerchantIds() {
        var sms = mock(GrizzlySmsClient.class);
        var service = new AccountRegistrationServiceImpl(null, null, null, sms, null, null, false, null);
        when(sms.getPriceTiers("wa", "187")).thenReturn(List.of(new GrizzlyPriceTier("187", "wa", new BigDecimal("0.88"), 20, List.of())));
        service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, null);
        assertThatThrownBy(() -> service.requireAvailableTier("wa", "187", new BigDecimal("0.88"), 1, "196"))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
    }
}
