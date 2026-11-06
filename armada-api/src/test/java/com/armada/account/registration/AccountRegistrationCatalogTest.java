package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.impl.AccountRegistrationServiceImpl;
import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.armada.platform.sms.grizzly.model.GrizzlyCountry;
import com.armada.platform.sms.grizzly.model.GrizzlyPrice;
import com.armada.platform.sms.grizzly.model.GrizzlyService;
import com.armada.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 国家目录不再只限美国，并带库存、按库存倒序。 */
class AccountRegistrationCatalogTest {
    private final GrizzlySmsClient grizzly = mock(GrizzlySmsClient.class);
    private final GrizzlySmsProperties properties = new GrizzlySmsProperties();
    private AccountRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        properties.setApiKey("test-only-key");
        service = new AccountRegistrationServiceImpl(null, null, null, grizzly, properties,
                mock(CobaltRegistrationClient.class), false, new MockEnvironment());
        when(grizzly.getServices()).thenReturn(List.of(new GrizzlyService("wa", "WhatsApp")));
        when(grizzly.getCountries()).thenReturn(List.of(
                new GrizzlyCountry("6", "Indonesia", Optional.of("印度尼西亚"), Optional.empty()),
                new GrizzlyCountry("187", "USA", Optional.of("美国"), Optional.empty()),
                new GrizzlyCountry("22", "India", Optional.of("印度"), Optional.empty())));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void allCountriesAreListedWithStockAndSortedByStockDescending() {
        when(grizzly.getServicePrices("wa")).thenReturn(List.of(
                new GrizzlyPrice("6", "wa", new BigDecimal("0.30"), 50),
                new GrizzlyPrice("187", "wa", new BigDecimal("0.15"), 125310)));

        var countries = service.catalog().countries();

        assertThat(countries).extracting("id").containsExactly("187", "6", "22");
        assertThat(countries.get(0).stock()).isEqualTo(125310);
        assertThat(countries.get(0).price()).isEqualByComparingTo("0.15");
        assertThat(countries.get(2).stock()).isNull();
    }

    @Test
    void quotesAreCachedAndFailureOnlyDropsStock() {
        when(grizzly.getServicePrices("wa")).thenThrow(new IllegalStateException("down"));

        var countries = service.catalog().countries();
        service.catalog();

        assertThat(countries).hasSize(3).allMatch(country -> country.stock() == null);

        doReturn(List.of(new GrizzlyPrice("22", "wa", BigDecimal.ONE, 7))).when(grizzly).getServicePrices("wa");
        // 失败不写缓存，下一次会重试。
        assertThat(service.catalog().countries().get(0).id()).isEqualTo("22");
        service.catalog();
        verify(grizzly, times(3)).getServicePrices("wa");
    }
}
