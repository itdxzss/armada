package com.armada.platform.country.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.country.model.entity.Country;
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
    void countActive_returnsSeeded248Rows() {
        assertThat(mapper.countActive()).isEqualTo(248);
    }

    @Test
    void selectIpSupported_returnsSeededRowsInSortOrder() {
        List<Country> rows = mapper.selectIpSupported();

        assertThat(rows).hasSize(248);
        assertThat(rows.get(0).getIso2()).isEqualTo("AF");
        assertThat(rows.get(0).getNameZh()).isEqualTo("阿富汗");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getIso2()).isEqualTo("IN");
            assertThat(row.getNameZh()).isEqualTo("印度");
            assertThat(row.getPhonePrefix()).isEqualTo("+91");
            assertThat(row.getFlag()).isEqualTo("🇮🇳");
        });
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
}
