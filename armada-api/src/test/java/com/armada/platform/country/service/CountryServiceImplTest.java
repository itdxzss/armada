package com.armada.platform.country.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.impl.CountryServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
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

    @Test
    void resolveIpRegionByPhonePrefix_usesLongestNumericPrefixAndReturnsNullWhenUnmatched() {
        when(mapper.selectIpSupported()).thenReturn(List.of(
                country("US", "美国", "+1", "🇺🇸"),
                country("AS", "美属萨摩亚", "+1-684", "🇦🇸"),
                country("IN", "印度", "+91", "🇮🇳")));

        assertThat(service.resolveIpRegionByPhonePrefix("+91 9876543210")).isEqualTo("印度");
        assertThat(service.resolveIpRegionByPhonePrefix("16841234567@s.whatsapp.net")).isEqualTo("美属萨摩亚");
        assertThat(service.resolveIpRegionByPhonePrefix("8613812345678")).isNull();
    }

    @Test
    void resolveIpRegionsByPhonePrefix_queriesCountriesOnceForBatch() {
        when(mapper.selectIpSupported()).thenReturn(List.of(
                country("US", "美国", "+1", "🇺🇸"),
                country("AS", "美属萨摩亚", "+1-684", "🇦🇸"),
                country("IN", "印度", "+91", "🇮🇳")));

        Map<String, String> result = service.resolveIpRegionsByPhonePrefix(List.of(
                "+91 9876543210",
                "16841234567@s.whatsapp.net",
                "8613812345678"));

        assertThat(result)
                .containsEntry("+91 9876543210", "印度")
                .containsEntry("16841234567@s.whatsapp.net", "美属萨摩亚")
                .containsEntry("8613812345678", null);
        verify(mapper).selectIpSupported();
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
