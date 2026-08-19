package com.armada.platform.country.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.entity.CountryPhonePrefixMapping;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.model.vo.CountryReferenceVO;
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
        Country india = country("IN", "印度", "+91", "🇮🇳");
        india.setContinentCode("ASIA");
        when(mapper.selectIpSupported()).thenReturn(List.of(india));

        CountryOptionsVO result = service.options("ip");

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).value()).isEqualTo("MIXED");
        assertThat(result.rows().get(0).nameZh()).isEqualTo("混合（不限国家）");
        assertThat(result.rows().get(0).virtual()).isTrue();
        assertThat(result.rows().get(0).continentCode()).isNull();
        assertThat(result.rows().get(1).value()).isEqualTo("IN");
        assertThat(result.rows().get(1).phonePrefix()).isEqualTo("+91");
        assertThat(result.rows().get(1).virtual()).isFalse();
        assertThat(result.rows().get(1).continentCode()).isEqualTo("ASIA");
    }

    @Test
    void options_marketingExportScopeReturnsOnlyEnabledRealCountriesWithEnglishName() {
        Country india = country("IN", "印度", "+91", "🇮🇳");
        india.setNameEn("India");
        when(mapper.selectEnabled()).thenReturn(List.of(india));

        CountryOptionsVO result = service.options("marketing-export");

        assertThat(result.rows()).singleElement().satisfies(option -> {
            assertThat(option.value()).isEqualTo("IN");
            assertThat(option.iso2()).isEqualTo("IN");
            assertThat(option.nameZh()).isEqualTo("印度");
            assertThat(option.nameEn()).isEqualTo("India");
            assertThat(option.phonePrefix()).isEqualTo("+91");
            assertThat(option.virtual()).isFalse();
        });
    }

    @Test
    void resolveActiveOptionsByPhonePrefix_usesLongestRuleAndConfiguredUniqueMapping() {
        Country unitedStates = country("US", "美国", "+1", "🇺🇸");
        Country canada = country("CA", "加拿大", "+1", "🇨🇦");
        Country puertoRico = country("PR", "波多黎各", "+1-787/939", "🇵🇷");
        when(mapper.selectEnabled()).thenReturn(List.of(canada, puertoRico, unitedStates));
        when(mapper.selectPhonePrefixMappings()).thenReturn(List.of(prefixMapping("1", "US")));

        Map<String, CountryOptionVO> result = service.resolveActiveOptionsByPhonePrefix(List.of(
                "17875550123",
                "+1 939 555 0123",
                "14165550123",
                "999"));

        assertThat(result)
                .extractingByKey("17875550123")
                .satisfies(option -> assertThat(option.iso2()).isEqualTo("PR"));
        assertThat(result)
                .extractingByKey("+1 939 555 0123")
                .satisfies(option -> assertThat(option.iso2()).isEqualTo("PR"));
        assertThat(result)
                .extractingByKey("14165550123")
                .satisfies(option -> assertThat(option.iso2()).isEqualTo("US"));
        assertThat(result).doesNotContainKey("999");
    }

    @Test
    void resolveActiveOptionsByPhonePrefix_doesNotGuessWhenSharedPrefixHasNoMapping() {
        when(mapper.selectEnabled()).thenReturn(List.of(
                country("US", "美国", "+1", "🇺🇸"),
                country("CA", "加拿大", "+1", "🇨🇦")));
        when(mapper.selectPhonePrefixMappings()).thenReturn(List.of());

        Map<String, CountryOptionVO> result =
                service.resolveActiveOptionsByPhonePrefix(List.of("14165550123"));

        assertThat(result).doesNotContainKey("14165550123");
    }

    @Test
    void activePhonePrefixResolverLoadsCatalogOnceAndResolvesWithoutMoreQueries() {
        Country unitedStates = country("US", "美国", "+1", "🇺🇸");
        Country canada = country("CA", "加拿大", "+1", "🇨🇦");
        Country india = country("IN", "印度", "+91", "🇮🇳");
        when(mapper.selectEnabled()).thenReturn(List.of(canada, india, unitedStates));
        when(mapper.selectPhonePrefixMappings()).thenReturn(List.of(prefixMapping("1", "US")));

        CountryService.PhonePrefixResolver resolver = service.activePhonePrefixResolver();

        assertThat(resolver.resolve("14165550123").iso2()).isEqualTo("US");
        assertThat(resolver.resolve("919876543210").iso2()).isEqualTo("IN");
        assertThat(resolver.resolve("999")).isNull();
        verify(mapper).selectEnabled();
        verify(mapper).selectPhonePrefixMappings();
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

    @Test
    void resolveActiveCountriesByPhoneNumbers_requiresValidInternationalNumbers() {
        when(mapper.selectActive()).thenReturn(List.of(
                country("CA", "加拿大", "+1", "🇨🇦"),
                country("US", "美国", "+1", "🇺🇸"),
                country("PE", "秘鲁", "+51", "🇵🇪"),
                country("KE", "肯尼亚", "+254", "🇰🇪")));

        Map<String, CountryReferenceVO> result = service.resolveActiveCountriesByPhoneNumbers(
                List.of(
                        "14165550123@s.whatsapp.net",
                        "12025550123",
                        "51943333070",
                        "254713151300",
                        "193088878297313",
                        "12306742263892",
                        "193088878297313@lid"));

        assertThat(result.get("14165550123@s.whatsapp.net").iso2()).isEqualTo("CA");
        assertThat(result.get("12025550123").iso2()).isEqualTo("US");
        assertThat(result.get("51943333070").iso2()).isEqualTo("PE");
        assertThat(result.get("254713151300").iso2()).isEqualTo("KE");
        assertThat(result).doesNotContainKeys(
                "193088878297313", "12306742263892", "193088878297313@lid");
        verify(mapper).selectActive();
    }

    @Test
    void resolveActiveCountriesByPhoneNumbers_normalizesLegacyMexicoWhatsAppMobilePrefix() {
        Country mexico = country("MX", "墨西哥", "+52", "🇲🇽");
        mexico.setContinentCode("NORTH_AMERICA");
        when(mapper.selectActive()).thenReturn(List.of(mexico));

        Map<String, CountryReferenceVO> result = service.resolveActiveCountriesByPhoneNumbers(
                List.of(
                        "5214438673076@s.whatsapp.net",
                        "5217541087825",
                        "+524438673076"));

        assertThat(result).containsOnlyKeys(
                "5214438673076@s.whatsapp.net",
                "5217541087825",
                "+524438673076");
        assertThat(result.values())
                .allSatisfy(country -> {
                    assertThat(country.iso2()).isEqualTo("MX");
                    assertThat(country.continentCode()).isEqualTo("NORTH_AMERICA");
                });
    }

    @Test
    void resolveActiveCountriesByPhoneNumbers_keepsValidMobileFormatsFromOtherCountries() {
        Country argentina = country("AR", "阿根廷", "+54", "🇦🇷");
        argentina.setContinentCode("SOUTH_AMERICA");
        Country brazil = country("BR", "巴西", "+55", "🇧🇷");
        brazil.setContinentCode("SOUTH_AMERICA");
        when(mapper.selectActive()).thenReturn(List.of(argentina, brazil));

        Map<String, CountryReferenceVO> result = service.resolveActiveCountriesByPhoneNumbers(
                List.of(
                        "5491123456789@s.whatsapp.net",
                        "5511987654321@s.whatsapp.net"));

        assertThat(result.get("5491123456789@s.whatsapp.net").iso2()).isEqualTo("AR");
        assertThat(result.get("5511987654321@s.whatsapp.net").iso2()).isEqualTo("BR");
    }

    @Test
    void resolveActiveCountriesByPhoneNumbers_omitsUnknownInputsAndDisabledCountries() {
        assertThat(service.resolveActiveCountriesByPhoneNumbers(null)).isEmpty();
        assertThat(service.resolveActiveCountriesByPhoneNumbers(List.of())).isEmpty();
        when(mapper.selectActive()).thenReturn(List.of(
                country("US", "美国", "+1", "🇺🇸")));

        Map<String, CountryReferenceVO> result = service.resolveActiveCountriesByPhoneNumbers(
                List.of("", "12A34", "4915123456789@lid", "4915123456789", "+12025550123"));

        assertThat(result).containsOnlyKeys("+12025550123");
        assertThat(result.get("+12025550123").iso2()).isEqualTo("US");
        verify(mapper).selectActive();
    }

    @Test
    void countryOptions_validateIso2AndLoadPageDisplayDataInBatch() {
        Country india = country("IN", "印度", "+91", "🇮🇳");
        when(mapper.selectActiveByIso2("IN")).thenReturn(india);
        when(mapper.selectByIso2s(List.of("IN"))).thenReturn(List.of(india));

        assertThat(service.requireActiveOption(" in ", true).nameZh()).isEqualTo("印度");
        assertThat(service.requireActiveOption("mixed", true))
                .extracting(CountryOptionVO::value, CountryOptionVO::virtual)
                .containsExactly("MIXED", true);
        assertThat(service.optionsByValues(List.of("IN", "IN", "MIXED")))
                .containsOnlyKeys("IN", "MIXED")
                .extractingByKey("IN")
                .satisfies(option -> assertThat(option.phonePrefix()).isEqualTo("+91"));

        verify(mapper).selectByIso2s(List.of("IN"));
    }

    @Test
    void countryOptions_rejectMixedWhenRealCountryIsRequired() {
        assertThatThrownBy(() -> service.requireActiveOption("MIXED", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预选区号");
    }

    @Test
    void legacyIdReferencesRemainAvailableForOtherBusinessDomains() {
        Country india = country("IN", "印度", "+91", "🇮🇳");
        india.setId(101L);
        when(mapper.selectActiveById(101L)).thenReturn(india);
        when(mapper.selectByIds(List.of(101L))).thenReturn(List.of(india));

        assertThat(service.requireActiveReference(101L).nameZh()).isEqualTo("印度");
        assertThat(service.referencesByIds(List.of(101L, 101L)))
                .containsOnlyKeys(101L)
                .extractingByKey(101L)
                .satisfies(reference -> assertThat(reference.phonePrefix()).isEqualTo("+91"));
    }

    private static Country country(String iso2, String nameZh, String phonePrefix, String flag) {
        Country country = new Country();
        country.setIso2(iso2);
        country.setNameZh(nameZh);
        country.setPhonePrefix(phonePrefix);
        country.setFlag(flag);
        return country;
    }

    private static CountryPhonePrefixMapping prefixMapping(String prefix, String iso2) {
        CountryPhonePrefixMapping mapping = new CountryPhonePrefixMapping();
        mapping.setNormalizedPrefix(prefix);
        mapping.setCountryIso2(iso2);
        return mapping;
    }

}
