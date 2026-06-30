package com.armada.platform.country.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.impl.CountryServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CountryServiceImplTest {

    @Mock
    private CountryMapper mapper;

    @InjectMocks
    private CountryServiceImpl service;

    @Test
    void options_ipScopePrependsMixedThenCountries() {
        when(mapper.selectIpSupported()).thenReturn(List.of(country("IN", "印度", "+91", "🇮🇳")));

        CountryOptionsVO result = service.options("ip");

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).value()).isEqualTo("MIXED");
        assertThat(result.rows().get(0).nameZh()).isEqualTo("混合（不限国家）");
        assertThat(result.rows().get(0).virtual()).isTrue();
        assertThat(result.rows().get(1).value()).isEqualTo("IN");
        assertThat(result.rows().get(1).phonePrefix()).isEqualTo("+91");
        assertThat(result.rows().get(1).virtual()).isFalse();
    }

    @Test
    void resolveIpRegion_supportsMixedIso2AndLegacyChinese() {
        when(mapper.selectActiveByIso2("IN")).thenReturn(country("IN", "印度", "+91", "🇮🇳"));
        when(mapper.selectActiveByNameZh("印度")).thenReturn(country("IN", "印度", "+91", "🇮🇳"));

        assertThat(service.resolveIpRegion("MIXED")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("mixed")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("混合（不限国家）")).isEqualTo("混合（不限国家）");
        assertThat(service.resolveIpRegion("IN")).isEqualTo("印度");
        assertThat(service.resolveIpRegion("印度")).isEqualTo("印度");
        assertThat(service.resolveIpRegion("  ")).isNull();
    }

    @Test
    void resolveIpRegion_unknownValueThrowsValidation() {
        assertThatThrownBy(() -> service.resolveIpRegion("ZZ"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("国家不存在或已停用");
                });
    }

    private static Country country(String iso2, String nameZh, String phonePrefix, String flag) {
        Country country = new Country();
        country.setIso2(iso2);
        country.setNameZh(nameZh);
        country.setPhonePrefix(phonePrefix);
        country.setFlag(flag);
        return country;
    }
}
