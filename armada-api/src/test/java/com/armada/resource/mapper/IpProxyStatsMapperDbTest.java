package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.IpProxyAllocationMode;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyCountryStatsRow;
import com.armada.resource.model.vo.IpProxyStatsDetailRow;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import com.armada.testsupport.DbTestBase;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IP 数据统计 Mapper 真库测试。
 *
 * <p>覆盖 summary、国家/地区聚合、风险筛选和明细 SQL,确保聚合分页在数据库侧完成。</p>
 */
class IpProxyStatsMapperDbTest extends DbTestBase {

    private static final String MIXED_REGION = "混合（不限国家）";

    @Autowired
    private IpProxyMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void statsQueriesAggregateActiveRowsByRegionAndExposeSampleCheckTime() {
        long now = System.currentTimeMillis();
        String marker = "ip-stats-dbtest-" + now;
        String india = "印度-" + marker;
        String pakistan = "巴基斯坦-" + marker;
        String malaysia = "马来西亚-" + marker;

        IpProxyStatsSummaryVO before = mapper.selectStatsSummary();

        insertCountry("XA", india, now, now + 50);
        insertCountry("XB", pakistan, now + 1);
        insertCountry("XC", malaysia, now + 2);
        IpProxy indiaIdleA = insertProxy(india, marker, IpProxyStatus.IDLE.code(), now);
        insertProxy(india, marker, IpProxyStatus.IDLE.code(), now + 1);
        IpProxy indiaUsing = insertProxy(india, marker, IpProxyStatus.IDLE.code(), now + 2);
        mapper.markUsingAndBind(
                indiaUsing.getId(),
                9001L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 20);
        insertProxy(india, marker, IpProxyStatus.UNAVAILABLE.code(), now + 3);

        IpProxy pakistanUsing = insertProxy(pakistan, marker, IpProxyStatus.IDLE.code(), now + 4);
        mapper.markUsingAndBind(
                pakistanUsing.getId(),
                9002L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 21);

        IpProxy deletedMalaysia = insertProxy(malaysia, marker, IpProxyStatus.IDLE.code(), now + 5);
        mapper.softDeleteByIds(List.of(deletedMalaysia.getId()), now + 30);
        insertProxy(MIXED_REGION, marker, IpProxyStatus.IDLE.code(), now + 6);
        jdbcTemplate.update(
                "UPDATE ip_proxy SET last_sample_check_at = ? WHERE id = ?",
                now + 40,
                indiaIdleA.getId());
        jdbcTemplate.update(
                "UPDATE ip_proxy SET check_fail_count = ? WHERE id = ?",
                2,
                indiaIdleA.getId());

        IpProxyStatsSummaryVO after = mapper.selectStatsSummary();
        assertThat(after.totalIpCount()).isEqualTo(before.totalIpCount() + 6);
        assertThat(after.idleIpCount()).isEqualTo(before.idleIpCount() + 3);
        assertThat(after.inUseIpCount()).isEqualTo(before.inUseIpCount() + 2);
        assertThat(after.unavailableIpCount()).isEqualTo(before.unavailableIpCount() + 1);
        assertThat(after.coveredRegionCount()).isEqualTo(before.coveredRegionCount() + 2);
        assertThat(after.supportedCountryCount()).isEqualTo(before.supportedCountryCount() + 3);
        assertThat(after.noIpCountryCount()).isEqualTo(before.noIpCountryCount() + 1);

        IpProxyStatsCountryQuery countryQuery = new IpProxyStatsCountryQuery();
        countryQuery.setKeyword(marker);
        countryQuery.setSource(marker);
        countryQuery.setPage(1);
        countryQuery.setPageSize(10);
        countryQuery.setSortField("totalIpCount");
        countryQuery.setSortOrder("desc");

        assertThat(mapper.countCountryStats(countryQuery)).isEqualTo(3);
        List<IpProxyCountryStatsRow> countryRows = mapper.selectCountryStatsPage(countryQuery);
        assertThat(countryRows).extracting(IpProxyCountryStatsRow::getRegion)
                .containsExactly(india, pakistan, malaysia);
        IpProxyCountryStatsRow indiaRow = countryRows.get(0);
        assertThat(indiaRow.getTotalIpCount()).isEqualTo(4);
        assertThat(indiaRow.getIdleIpCount()).isEqualTo(2);
        assertThat(indiaRow.getInUseIpCount()).isEqualTo(1);
        assertThat(indiaRow.getUnavailableIpCount()).isEqualTo(1);
        assertThat(indiaRow.getLastSampleCheckAt()).isEqualTo(now + 50);
        IpProxyCountryStatsRow noIpRow = countryRows.get(2);
        assertThat(noIpRow.getTotalIpCount()).isZero();

        countryQuery.setSortField("availableRate");
        countryQuery.setSortOrder("asc");
        assertThat(mapper.selectCountryStatsPage(countryQuery)).extracting(IpProxyCountryStatsRow::getRegion)
                .containsExactly(malaysia, india, pakistan);

        countryQuery.setRisk("no_idle");
        List<IpProxyCountryStatsRow> noIdleRows = mapper.selectCountryStatsPage(countryQuery);
        assertThat(noIdleRows).extracting(IpProxyCountryStatsRow::getRegion).containsExactly(pakistan);

        IpProxyStatsDetailQuery detailQuery = new IpProxyStatsDetailQuery();
        detailQuery.setPage(1);
        detailQuery.setPageSize(10);
        List<IpProxyStatsDetailRow> details = mapper.selectStatsDetailPage(india, detailQuery);

        assertThat(details).hasSize(4);
        IpProxyStatsDetailRow sampled = details.stream()
                .filter(row -> row.getId().equals(indiaIdleA.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(sampled.getProxyAddress()).isEqualTo(indiaIdleA.getHost() + ":" + indiaIdleA.getPort());
        assertThat(sampled.getProxyHost()).isEqualTo(indiaIdleA.getHost());
        assertThat(sampled.getProxyPort()).isEqualTo(indiaIdleA.getPort());
        assertThat(sampled.getAllocationMode()).isEqualTo(IpProxyAllocationMode.SMART.value());
        assertThat(sampled.getLastSampleCheckAt()).isEqualTo(now + 40);
        assertThat(sampled.getFailCount()).isEqualTo(2);
        assertThat(mapper.countStatsDetail(india, detailQuery)).isEqualTo(4);

        detailQuery.setAllocationMode(IpProxyAllocationMode.MIXED.value());
        assertThat(mapper.countStatsDetail(india, detailQuery)).isZero();
        assertThat(Arrays.stream(IpProxyStatsDetailVO.class.getDeclaredFields()).map(Field::getName))
                .contains("failCount")
                .doesNotContain("password", "createdAt", "boundAt");
    }

    @Test
    void detailQueryUsesSameTrimmedRegionAsCountryAggregation() {
        long now = System.currentTimeMillis();
        String marker = "ip-stats-trim-dbtest-" + now;
        String displayRegion = "印度-trim-" + now;
        insertCountry("XT", displayRegion, now);
        insertProxy(" " + displayRegion + " ", marker, IpProxyStatus.IDLE.code(), now);

        IpProxyStatsCountryQuery countryQuery = new IpProxyStatsCountryQuery();
        countryQuery.setKeyword(marker);
        countryQuery.setSource(marker);
        List<IpProxyCountryStatsRow> countryRows = mapper.selectCountryStatsPage(countryQuery);
        assertThat(countryRows).extracting(IpProxyCountryStatsRow::getRegion).containsExactly(displayRegion);

        IpProxyStatsDetailQuery detailQuery = new IpProxyStatsDetailQuery();
        List<IpProxyStatsDetailRow> details = mapper.selectStatsDetailPage(displayRegion, detailQuery);
        assertThat(details).hasSize(1);
        assertThat(mapper.countStatsDetail(displayRegion, detailQuery)).isEqualTo(1);
    }

    @Test
    void detailQuerySeparatesIpAndBoundAccountFilters() {
        long now = System.currentTimeMillis();
        String marker = "ip-stats-detail-search-dbtest-" + now;
        String region = "印度-detail-search-" + now;
        insertCountry("XD", region, now);
        IpProxy matched = insertProxy(region, marker, IpProxyStatus.IDLE.code(), now);
        mapper.markUsingAndBind(
                matched.getId(),
                99001L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 10);
        IpProxy otherAccount = insertProxy(region, marker, IpProxyStatus.IDLE.code(), now + 1);
        mapper.markUsingAndBind(
                otherAccount.getId(),
                88001L,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now + 11);
        insertProxy(region, marker, IpProxyStatus.IDLE.code(), now + 2);

        IpProxyStatsDetailQuery query = new IpProxyStatsDetailQuery();
        query.setIpKeyword(matched.getHost());
        query.setAccountKeyword("99001");
        query.setPage(1);
        query.setPageSize(10);

        assertThat(mapper.selectStatsDetailPage(region, query))
                .extracting(IpProxyStatsDetailRow::getId)
                .containsExactly(matched.getId());
        assertThat(mapper.countStatsDetail(region, query)).isEqualTo(1);

        query.setAccountKeyword("88001");
        assertThat(mapper.countStatsDetail(region, query)).isZero();
    }

    @Test
    void riskFiltersMatchDisplayedRiskPriority() {
        long now = System.currentTimeMillis();
        String marker = "ip-stats-risk-dbtest-" + now;
        String normal = "正常-" + marker;
        String noIdle = "无空闲-" + marker;
        String lowAvailable = "可用不足-" + marker;
        String highUnavailable = "不可用偏高-" + marker;
        String noIp = "无IP-" + marker;

        insertCountry("XN", normal, now);
        insertCountry("XO", noIdle, now + 1);
        insertCountry("XP", lowAvailable, now + 2);
        insertCountry("XQ", highUnavailable, now + 3);
        insertCountry("XR", noIp, now + 4);
        insertProxy(normal, marker, IpProxyStatus.IDLE.code(), now);
        insertProxy(normal, marker, IpProxyStatus.IN_USE.code(), now + 1);

        insertProxy(noIdle, marker, IpProxyStatus.IN_USE.code(), now + 2);

        insertProxy(lowAvailable, marker, IpProxyStatus.IDLE.code(), now + 3);
        for (int i = 0; i < 5; i++) {
            insertProxy(lowAvailable, marker, IpProxyStatus.UNAVAILABLE.code(), now + 4 + i);
        }

        insertProxy(highUnavailable, marker, IpProxyStatus.IDLE.code(), now + 20);
        insertProxy(highUnavailable, marker, IpProxyStatus.IN_USE.code(), now + 21);
        for (int i = 0; i < 3; i++) {
            insertProxy(highUnavailable, marker, IpProxyStatus.UNAVAILABLE.code(), now + 22 + i);
        }

        assertRiskRegions(marker, "normal", List.of(normal));
        assertRiskRegions(marker, "no_idle", List.of(noIdle));
        assertRiskRegions(marker, "low_available", List.of(lowAvailable));
        assertRiskRegions(marker, "high_unavailable", List.of(highUnavailable));
        assertRiskRegions(marker, "no_ip", List.of(noIp));
    }

    private void insertCountry(String iso2, String nameZh, long suffix) {
        insertCountry(iso2, nameZh, suffix, null);
    }

    private void insertCountry(String iso2, String nameZh, long suffix, Long lastIpSampleCheckAt) {
        jdbcTemplate.update(
                """
                INSERT INTO country
                  (iso2, name_zh, name_en, phone_prefix, flag, is_enabled, is_ip_supported,
                   sort_order, last_ip_sample_check_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?)
                """,
                iso2,
                nameZh,
                nameZh,
                "+0",
                "🏳",
                Math.floorMod(suffix, 100_000L),
                lastIpSampleCheckAt,
                suffix,
                suffix);
    }

    private IpProxy insertProxy(String region, String source, int status, long suffix) {
        IpProxy proxy = new IpProxy();
        proxy.setHost("proxy-" + suffix + ".internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("user" + suffix);
        proxy.setPassword("pass_session-Abc123" + suffix);
        proxy.setRegion(region);
        proxy.setStatus(status);
        proxy.setSource(source);
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setAllocationMode(IpProxyAllocationMode.SMART.value());
        proxy.setCreatedAt(suffix);
        proxy.setUpdatedAt(suffix);
        mapper.insert(proxy);
        return proxy;
    }

    private void assertRiskRegions(String marker, String risk, List<String> regions) {
        IpProxyStatsCountryQuery query = new IpProxyStatsCountryQuery();
        query.setKeyword(marker);
        query.setSource(marker);
        query.setRisk(risk);
        query.setPage(1);
        query.setPageSize(10);
        query.setSortField("totalIpCount");
        query.setSortOrder("desc");

        assertThat(mapper.countCountryStats(query)).isEqualTo(regions.size());
        assertThat(mapper.selectCountryStatsPage(query)).extracting(IpProxyCountryStatsRow::getRegion)
                .containsExactlyElementsOf(regions);
    }
}
