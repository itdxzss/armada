package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.dto.IpProxyCountrySampleCheckDTO;
import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.vo.IpProxyCheckResultVO;
import com.armada.resource.model.vo.IpProxyCountrySampleCheckVO;
import com.armada.resource.model.vo.IpProxyCountrySampleStatsVO;
import com.armada.resource.model.vo.IpProxyCountryStatsRow;
import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailRow;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.service.impl.IpProxyStatsServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private IpProxyService ipProxyService;

    @Mock
    private CountryMapper countryMapper;

    private IpProxyStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IpProxyStatsServiceImpl(mapper, ipProxyService, countryMapper);
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
    void sampleCheckRegion_checksRandomCountryIpsAndUpdatesCountrySampleTime() {
        IpProxyCountrySampleCheckDTO request = new IpProxyCountrySampleCheckDTO(3);
        List<Long> sampleIds = List.of(11L, 12L, 13L);
        when(mapper.selectCountrySampleStatsByRegion("印度"))
                .thenReturn(new IpProxyCountrySampleStatsVO("印度", 3L, 2L, 0L, 1L));
        when(mapper.selectSampleActiveIdsByRegion("印度", 3)).thenReturn(sampleIds);
        when(ipProxyService.checkProxies(sampleIds)).thenReturn(List.of(
                new IpProxyCheckResultVO(11L, "success", "空闲", "success", "1.1.1.1", "IN", "印度",
                        "Mumbai", "ISP", null, null, 1_719_800_000_000L, null),
                new IpProxyCheckResultVO(12L, "failed", "不可用", "failed", null, null, "印度",
                        null, null, null, null, 1_719_800_000_100L, "代理检测失败"),
                new IpProxyCheckResultVO(13L, "success", "空闲", "success", "1.1.1.3", "IN", "印度",
                        "Delhi", "ISP", null, null, 1_719_800_000_200L, null)));
        when(countryMapper.updateLastIpSampleCheckAtByNameZh(eq("印度"), anyLong())).thenReturn(1);

        IpProxyCountrySampleCheckVO result = service.sampleCheckRegion(" 印度 ", request);

        assertThat(result.region()).isEqualTo("印度");
        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(result.results()).hasSize(3);
        ArgumentCaptor<Long> checkedAtCaptor = ArgumentCaptor.forClass(Long.class);
        verify(countryMapper).updateLastIpSampleCheckAtByNameZh(eq("印度"), checkedAtCaptor.capture());
        assertThat(result.lastSampleCheckAt()).isEqualTo(checkedAtCaptor.getValue());
    }

    @Test
    void sampleCheckRegion_allowsMoreThanExistingBatchSizeAndChecksInBatches() {
        IpProxyCountrySampleCheckDTO request = new IpProxyCountrySampleCheckDTO(21);
        List<Long> sampleIds = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();
        List<IpProxyCheckResultVO> firstBatchResults = java.util.stream.LongStream.rangeClosed(1, 20)
                .mapToObj(id -> new IpProxyCheckResultVO(id, "success", "空闲", "success",
                        "1.1.1." + id, "IN", "印度", "Mumbai", "ISP", null, null, 1_719_800_000_000L + id, null))
                .toList();
        IpProxyCheckResultVO lastResult = new IpProxyCheckResultVO(21L, "success", "空闲", "success",
                "1.1.1.21", "IN", "印度", "Delhi", "ISP", null, null, 1_719_800_000_021L, null);
        when(mapper.selectCountrySampleStatsByRegion("印度"))
                .thenReturn(new IpProxyCountrySampleStatsVO("印度", 21L, 18L, 2L, 1L));
        when(mapper.selectSampleActiveIdsByRegion("印度", 21)).thenReturn(sampleIds);
        when(ipProxyService.checkProxies(sampleIds.subList(0, 20))).thenReturn(firstBatchResults);
        when(ipProxyService.checkProxies(sampleIds.subList(20, 21))).thenReturn(List.of(lastResult));
        when(countryMapper.updateLastIpSampleCheckAtByNameZh(eq("印度"), anyLong())).thenReturn(1);

        IpProxyCountrySampleCheckVO result = service.sampleCheckRegion("印度", request);

        assertThat(result.sampleCount()).isEqualTo(21);
        assertThat(result.results()).hasSize(21);
        verify(ipProxyService).checkProxies(sampleIds.subList(0, 20));
        verify(ipProxyService).checkProxies(sampleIds.subList(20, 21));
    }

    @Test
    void countrySampleStats_returnsCountryCountsForSampleDialog() {
        IpProxyCountrySampleStatsVO stats = new IpProxyCountrySampleStatsVO("印度", 30L, 20L, 7L, 3L);
        when(mapper.selectCountrySampleStatsByRegion("印度")).thenReturn(stats);

        IpProxyCountrySampleStatsVO result = service.countrySampleStats(" 印度 ");

        assertThat(result).isEqualTo(stats);
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
        row.setFailCount(3);
        when(mapper.countStatsDetail("印度", query)).thenReturn(1L);
        when(mapper.selectStatsDetailPage("印度", query)).thenReturn(List.of(row));

        PageResult<IpProxyStatsDetailVO> result = service.regionProxies(" 印度 ", query);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(5);
        IpProxyStatsDetailVO vo = result.list().get(0);
        assertThat(vo.protocolLabel()).isEqualTo("SOCKS5");
        assertThat(vo.statusLabel()).isEqualTo("使用中");
        assertThat(vo.ownershipLabel()).isEqualTo("租户自有");
        assertThat(vo.lastSampleCheckAt()).isEqualTo(1_719_800_000_000L);
        assertThat(vo.failCount()).isEqualTo(3);
        verify(mapper).countStatsDetail("印度", query);
        verify(mapper).selectStatsDetailPage("印度", query);
    }

    @Test
    void regionProxies_exposesSeparatedAddressAndAllocationMode() throws Exception {
        assertThat(recordComponentNames(IpProxyStatsDetailVO.class))
                .contains("proxyHost", "proxyPort", "allocationMode", "allocationModeLabel", "failCount")
                .doesNotContain("createdAt", "boundAt", "password");

        IpProxyStatsDetailQuery query = new IpProxyStatsDetailQuery();
        IpProxyStatsDetailRow row = new IpProxyStatsDetailRow();
        row.setId(10L);
        row.getClass().getMethod("setProxyHost", String.class).invoke(row, "1.2.3.4");
        row.getClass().getMethod("setProxyPort", Integer.class).invoke(row, 8000);
        row.getClass().getMethod("setAllocationMode", String.class).invoke(row, "mixed");
        row.setProtocol(1);
        row.setRegion("印度");
        row.setStatus(1);
        row.setSource("iproyal");
        row.setOwnership(1);
        when(mapper.countStatsDetail("印度", query)).thenReturn(1L);
        when(mapper.selectStatsDetailPage("印度", query)).thenReturn(List.of(row));

        IpProxyStatsDetailVO vo = service.regionProxies("印度", query).list().get(0);

        assertThat(vo.getClass().getMethod("proxyHost").invoke(vo)).isEqualTo("1.2.3.4");
        assertThat(vo.getClass().getMethod("proxyPort").invoke(vo)).isEqualTo(8000);
        assertThat(vo.getClass().getMethod("allocationMode").invoke(vo)).isEqualTo("mixed");
        assertThat(vo.getClass().getMethod("allocationModeLabel").invoke(vo)).isEqualTo("混合分组");
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

    private static List<String> recordComponentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents()).map(component -> component.getName()).toList();
    }
}
