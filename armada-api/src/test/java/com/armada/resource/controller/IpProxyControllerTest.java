package com.armada.resource.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.vo.IpProxyCheckResultVO;
import com.armada.resource.model.vo.IpProxyImportSampleCheckVO;
import com.armada.resource.service.IpProxyDeletionService;
import com.armada.resource.service.IpProxyService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IP 管理 Controller 单测:只覆盖检测路由委托和 ApiResponse 包裹。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyControllerTest {

    @Mock
    private IpProxyService service;

    @Mock
    private IpProxyDeletionService deletionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IpProxyController(service, deletionService))
                .build();
    }

    @Test
    void sampleCheckImport_delegatesToServiceAndReturnsApiResponse() throws Exception {
        IpProxyImportSampleCheckVO vo = new IpProxyImportSampleCheckVO(
                true,
                1,
                List.of(new IpProxyImportSampleCheckVO.SampleRow(
                        1,
                        "1.1.1.1",
                        8080,
                        true,
                        "103.10.10.10",
                        "US",
                        "United States",
                        "success",
                        "HTTP 400",
                        "Example ISP",
                        1_719_800_000_000L,
                        null,
                        null,
                        null)),
                List.of());
        when(service.sampleCheckImport(any())).thenReturn(vo);

        mockMvc.perform(post("/api/ip-proxies/import/sample-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryValue":"US","protocol":1,"source":"供应商A","text":"1.1.1.1:8080:u:p"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.sampleSize").value(1))
                .andExpect(jsonPath("$.data.samples[0].lineNo").value(1))
                .andExpect(jsonPath("$.data.samples[0].host").value("1.1.1.1"))
                .andExpect(jsonPath("$.data.samples[0].passed").value(true))
                .andExpect(jsonPath("$.data.samples[0].connectionStatus").value("success"))
                .andExpect(jsonPath("$.data.samples[0].whatsappStatus").value("HTTP 400"))
                .andExpect(jsonPath("$.data.samples[0].isp").value("Example ISP"))
                .andExpect(jsonPath("$.data.samples[0].checkedAt").value(1_719_800_000_000L));

        verify(service).sampleCheckImport(any(IpProxyImportDTO.class));
    }

    @Test
    void checkProxy_delegatesToServiceAndReturnsApiResponse() throws Exception {
        IpProxyCheckResultVO vo = new IpProxyCheckResultVO(
                10L,
                "success",
                "空闲",
                "unknown",
                "103.10.10.10",
                "IN",
                "印度",
                "Mumbai",
                "Example ISP",
                new BigDecimal("19.0760000"),
                new BigDecimal("72.8777000"),
                1_719_800_000_000L,
                null);
        when(service.checkProxy(10L)).thenReturn(vo);

        mockMvc.perform(post("/api/ip-proxies/{id}/check", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.checkStatus").value("success"))
                .andExpect(jsonPath("$.data.connectionStatus").value("空闲"))
                .andExpect(jsonPath("$.data.whatsappStatus").value("unknown"))
                .andExpect(jsonPath("$.data.outboundIp").value("103.10.10.10"))
                .andExpect(jsonPath("$.data.countryCode").value("IN"))
                .andExpect(jsonPath("$.data.region").value("印度"))
                .andExpect(jsonPath("$.data.location").value("Mumbai"))
                .andExpect(jsonPath("$.data.isp").value("Example ISP"))
                .andExpect(jsonPath("$.data.detectedLatitude").value(19.0760000))
                .andExpect(jsonPath("$.data.detectedLongitude").value(72.8777000))
                .andExpect(jsonPath("$.data.checkedAt").value(1_719_800_000_000L));

        verify(service).checkProxy(10L);
    }

    @Test
    void checkProxies_delegatesToServiceAndReturnsApiResponseList() throws Exception {
        when(service.checkProxies(List.of(10L, 11L))).thenReturn(List.of(
                new IpProxyCheckResultVO(
                        10L, "success", "空闲", "unknown", "103.10.10.10", "IN", "印度",
                        "Mumbai", "Example ISP", null, null, 1_719_800_000_000L, null),
                new IpProxyCheckResultVO(
                        11L, "failed", "不可用", "unknown", null, null, "混合（不限国家）",
                        null, null, null, null, 1_719_800_000_100L, "代理连接超时")));

        mockMvc.perform(post("/api/ip-proxies/check")
                        .contentType("application/json")
                        .content("{\"ids\":[10,11]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].checkStatus").value("success"))
                .andExpect(jsonPath("$.data[1].id").value(11))
                .andExpect(jsonPath("$.data[1].checkStatus").value("failed"))
                .andExpect(jsonPath("$.data[1].errorMessage").value("代理连接超时"));

        verify(service).checkProxies(List.of(10L, 11L));
    }
}
