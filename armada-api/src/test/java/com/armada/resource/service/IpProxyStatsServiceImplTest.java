package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.vo.IpProxyCountryStatsRow;
import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailRow;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.service.impl.IpProxyStatsServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IP 数据统计业务单测:Mapper 只提供聚合原始值,Service 负责比例、风险和枚举 label。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyStatsServiceImplTest {

    @Mock
    private IpProxyMapper mapper;

    private IpProxyStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IpProxyStatsServiceImpl(mapper);
    }

    @Test
    void countries_calculatesRatesAndRiskFromMapperCounts() {
        IpProxyStatsCountryQuery query = new IpProxyStatsCountryQuery();
        query.setPage(1);
        query.setPageSize(10);
        IpProxyCountryStatsRow row = countryRow("印度", 4L, 2L, 1L, 1L);
        when(mapper.countCountryStats(query)).thenReturn(1L);
        when(mapper.selectCountryStatsPage(query)).thenReturn(List.of(row));

        PageResult<IpProxyCountryStatsVO> result = service.countries(query);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        IpProxyCountryStatsVO vo = result.list().get(0);
        assertThat(vo.region()).isEqualTo("印度");
        assertThat(vo.availableRate()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(vo.unavailableRate()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(vo.resourceRisk()).isEqualTo("normal");
        assertThat(vo.resourceRiskLabel()).isEqualTo("正常");
    }

    @Test
    void countries_marksNoIdleWhenRegionOnlyHasInUseIps() {
        IpProxyStatsCountryQuery query = new IpProxyStatsCountryQuery();
        IpProxyCountryStatsRow row = countryRow("巴基斯坦", 1L, 0L, 1L, 0L);
        when(mapper.countCountryStats(query)).thenReturn(1L);
        when(mapper.selectCountryStatsPage(query)).thenReturn(List.of(row));

        IpProxyCountryStatsVO vo = service.countries(query).list().get(0);

        assertThat(vo.availableRate()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(vo.resourceRisk()).isEqualTo("no_idle");
        assertThat(vo.resourceRiskLabel()).isEqualTo("无空闲 IP");
    }

    @Test
    void countries_marksNoIpWhenSupportedCountryHasNoIps() {
        IpProxyStatsCountryQuery query = new IpProxyStatsCountryQuery();
        query.setRisk("no_ip");
        IpProxyCountryStatsRow row = countryRow("巴西", 0L, 0L, 0L, 0L);
        when(mapper.countCountryStats(query)).thenReturn(1L);
        when(mapper.selectCountryStatsPage(query)).thenReturn(List.of(row));

        IpProxyCountryStatsVO vo = service.countries(query).list().get(0);

        assertThat(vo.availableRate()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(vo.resourceRisk()).isEqualTo("no_ip");
        assertThat(vo.resourceRiskLabel()).isEqualTo("无 IP");
    }

    @Test
    void regionProxies_mapsEnumLabelsAndPaginates() {
        IpProxyStatsDetailQuery query = new IpProxyStatsDetailQuery();
        query.setPage(2);
        query.setPageSize(5);
        IpProxyStatsDetailRow row = new IpProxyStatsDetailRow();
        row.setId(10L);
        row.setProxyAddress("1.2.3.4:8000");
        row.setProtocol(2);
        row.setRegion("印度");
        row.setStatus(2);
        row.setBoundAccountId(9001L);
        row.setSource("iproyal");
        row.setOwnership(1);
        row.setLastSampleCheckAt(1_719_800_000_000L);
        row.setCreatedAt(1_719_700_000_000L);
        row.setBoundAt(1_719_800_000_100L);
        when(mapper.countStatsDetail("印度", query)).thenReturn(1L);
        when(mapper.selectStatsDetailPage("印度", query)).thenReturn(List.of(row));

        PageResult<IpProxyStatsDetailVO> result = service.regionProxies(" 印度 ", query);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(5);
        IpProxyStatsDetailVO vo = result.list().get(0);
        assertThat(vo.protocolLabel()).isEqualTo("SOCKETS");
        assertThat(vo.statusLabel()).isEqualTo("使用中");
        assertThat(vo.ownershipLabel()).isEqualTo("租户自有");
        assertThat(vo.lastSampleCheckAt()).isEqualTo(1_719_800_000_000L);
        verify(mapper).countStatsDetail("印度", query);
        verify(mapper).selectStatsDetailPage("印度", query);
    }

    @Test
    void regionProxies_rejectsBlankRegion() {
        assertThatThrownBy(() -> service.regionProxies(" ", new IpProxyStatsDetailQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("国家/地区不能为空");
    }

    private static IpProxyCountryStatsRow countryRow(
            String region,
            Long totalIpCount,
            Long idleIpCount,
            Long inUseIpCount,
            Long unavailableIpCount) {
        IpProxyCountryStatsRow row = new IpProxyCountryStatsRow();
        row.setRegion(region);
        row.setTotalIpCount(totalIpCount);
        row.setIdleIpCount(idleIpCount);
        row.setInUseIpCount(inUseIpCount);
        row.setUnavailableIpCount(unavailableIpCount);
        return row;
    }
}
