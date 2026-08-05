package com.armada.platform.country.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.entity.CountryPhonePrefixMapping;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 国家/地区主数据 DbTest:验证 Flyway seed、租户拦截忽略表和 IP 下拉排序。
 */
class CountryMapperDbTest extends DbTestBase {

    @Autowired
    private CountryMapper mapper;

    @Test
    void countActive_returnsSeeded249Rows() {
        assertThat(mapper.countActive()).isEqualTo(249);
    }

    @Test
    void selectIpSupported_returnsSeededRowsInSortOrder() {
        List<Country> rows = mapper.selectIpSupported();

        assertThat(rows).hasSize(248);
        assertThat(rows.get(0).getIso2()).isEqualTo("AF");
        assertThat(rows.get(0).getNameZh()).isEqualTo("阿富汗");
        assertThat(rows.get(0).getNameEn()).isEqualTo("Afghanistan");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getIso2()).isEqualTo("IN");
            assertThat(row.getNameZh()).isEqualTo("印度");
            assertThat(row.getNameEn()).isEqualTo("India");
            assertThat(row.getPhonePrefix()).isEqualTo("+91");
            assertThat(row.getFlag()).isEqualTo("🇮🇳");
        });
        assertThat(rows).allSatisfy(row -> assertThat(row.getNameEn()).isNotBlank());
    }

    @Test
    void selectEnabled_returnsMarketingExportCountriesIncludingDiegoGarcia() {
        List<Country> rows = mapper.selectEnabled();

        assertThat(rows).hasSize(249);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getIso2()).isEqualTo("DG");
            assertThat(row.getNameZh()).isEqualTo("迪戈加西亚岛");
            assertThat(row.getNameEn()).isEqualTo("Diego Garcia");
            assertThat(row.getPhonePrefix()).isEqualTo("+246");
        });
    }

    @Test
    void selectPhonePrefixMappings_returnsUniqueConfiguredCountryForSharedPrefixes() {
        List<CountryPhonePrefixMapping> rows = mapper.selectPhonePrefixMappings();

        assertThat(rows)
                .extracting(CountryPhonePrefixMapping::getNormalizedPrefix)
                .doesNotHaveDuplicates();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getNormalizedPrefix()).isEqualTo("1");
            assertThat(row.getCountryIso2()).isEqualTo("US");
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getNormalizedPrefix()).isEqualTo("246");
            assertThat(row.getCountryIso2()).isEqualTo("DG");
        });
    }

    @Test
    void selectActive_remainsCompatibleWithAllEnabledCountries() {
        List<Country> rows = mapper.selectActive();

        assertThat(rows).hasSize(249);
        assertThat(rows.get(0).getIso2()).isEqualTo("AF");
        assertThat(rows).allSatisfy(row -> assertThat(row.getPhonePrefix()).isNotBlank());
    }

    @Test
    void selectActiveByIso2AndNameZh_ignoreTenantInterceptor() {
        Country byIso2 = mapper.selectActiveByIso2("IN");
        Country byName = mapper.selectActiveByNameZh("印度");

        assertThat(byIso2).isNotNull();
        assertThat(byIso2.getNameZh()).isEqualTo("印度");
        assertThat(byName).isNotNull();
        assertThat(byName.getIso2()).isEqualTo("IN");
    }

    @Test
    void continentCode_usesSixContinentCatalogAndLeavesAntarcticTerritoriesUnknown() {
        assertThat(mapper.selectActiveByIso2("CN").getContinentCode()).isEqualTo("ASIA");
        assertThat(mapper.selectActiveByIso2("US").getContinentCode()).isEqualTo("NORTH_AMERICA");
        assertThat(mapper.selectActiveByIso2("BR").getContinentCode()).isEqualTo("SOUTH_AMERICA");
        assertThat(mapper.selectActiveByIso2("AQ").getContinentCode()).isNull();

        assertThat(mapper.selectActive()).allSatisfy(country -> {
            if (!List.of("AQ", "BV", "HM", "TF").contains(country.getIso2())) {
                assertThat(country.getContinentCode()).isNotBlank();
            }
        });
    }

    @Test
    void selectByIso2s_returnsCountryOptionsInBatch() {
        assertThat(mapper.selectByIso2s(List.of("IN")))
                .singleElement()
                .satisfies(row -> assertThat(row.getNameZh()).isEqualTo("印度"));
    }

    @Test
    void legacyIdQueriesRemainAvailable() {
        Country india = mapper.selectActiveByIso2("IN");

        assertThat(mapper.selectActiveById(india.getId()).getIso2()).isEqualTo("IN");
        assertThat(mapper.selectByIds(List.of(india.getId())))
                .singleElement()
                .satisfies(row -> assertThat(row.getNameZh()).isEqualTo("印度"));
    }
}
