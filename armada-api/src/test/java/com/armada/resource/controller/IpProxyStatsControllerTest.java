package com.armada.resource.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import com.armada.resource.service.IpProxyStatsService;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IP 数据统计 Controller 单测:只验证路由、参数绑定和 ApiResponse 包裹。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyStatsControllerTest {

    @Mock
    private IpProxyStatsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IpProxyStatsController(service))
                .build();
    }

    @Test
    void summary_returnsStatsInApiResponse() throws Exception {
        when(service.summary()).thenReturn(new IpProxyStatsSummaryVO(10L, 2L, 7L, 1L, 3L, 12L, 9L));

        mockMvc.perform(get("/api/ip-proxies/stats/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalIpCount").value(10))
                .andExpect(jsonPath("$.data.inUseIpCount").value(2))
                .andExpect(jsonPath("$.data.coveredRegionCount").value(3))
                .andExpect(jsonPath("$.data.supportedCountryCount").value(12))
                .andExpect(jsonPath("$.data.noIpCountryCount").value(9));

        verify(service).summary();
    }

    @Test
    void countries_bindsQueryAndReturnsPagedRows() throws Exception {
        when(service.countries(argThat(query ->
                query != null
                        && "印度".equals(query.getKeyword())
                        && query.getProtocol().equals(1)
                        && "iproyal".equals(query.getSource())
                        && "normal".equals(query.getRisk())
                        && "totalIpCount".equals(query.getSortField())
                        && "desc".equals(query.getSortOrder())
                        && query.getPage() == 2
                        && query.getPageSize() == 20))).thenReturn(PageResult.of(List.of(
                new IpProxyCountryStatsVO(
                        "印度",
                        4L,
                        1L,
                        2L,
                        1L,
                        new BigDecimal("75.00"),
                        new BigDecimal("25.00"),
                        "normal",
                        "正常")), 2, 20, 1));

        mockMvc.perform(get("/api/ip-proxies/stats/countries")
                        .param("keyword", "印度")
                        .param("protocol", "1")
                        .param("source", "iproyal")
                        .param("risk", "normal")
                        .param("sortField", "totalIpCount")
                        .param("sortOrder", "desc")
                        .param("page", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.list[0].region").value("印度"))
                .andExpect(jsonPath("$.data.list[0].resourceRisk").value("normal"));
    }

    @Test
    void regionProxies_bindsRegionAndQueryAndReturnsPagedRows() throws Exception {
        when(service.regionProxies(argThat(region -> "印度".equals(region)), argThat(query ->
                query != null
                        && query.getStatus().equals(2)
                        && query.getProtocol().equals(1)
                        && "iproyal".equals(query.getSource())
                        && "1.2.3.4".equals(query.getKeyword())
                        && query.getPage() == 1
                        && query.getPageSize() == 10))).thenReturn(PageResult.of(List.of(
                new IpProxyStatsDetailVO(
                        101L,
                        "1.2.3.4:8000",
                        1,
                        "HTTP",
                        "印度",
                        2,
                        "使用中",
                        9001L,
                        "iproyal",
                        1,
                        "租户自有",
                        1_719_800_000_000L,
                        1_719_700_000_000L,
                        1_719_800_000_100L)), 1, 10, 1));

        mockMvc.perform(get("/api/ip-proxies/stats/countries/{region}/proxies", "印度")
                        .param("status", "2")
                        .param("protocol", "1")
                        .param("source", "iproyal")
                        .param("keyword", "1.2.3.4")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].proxyAddress").value("1.2.3.4:8000"))
                .andExpect(jsonPath("$.data.list[0].lastSampleCheckAt").value(1_719_800_000_000L));
    }
}
