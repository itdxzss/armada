package com.armada.promotion.channel.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PromotionChannelPublicControllerTest {

    @Mock
    private PromotionChannelService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PromotionChannelPublicController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void runtimeUsesChannelCodeAndForwardedHostAndReturnsMinimalConfiguration() throws Exception {
        when(service.runtime("bewbmr9k", "go.example.com")).thenReturn(
                new PromotionChannelRuntimeVO("DATE_V2", "#e11d48", true, "MIXED", "IN"));

        mockMvc.perform(get("/api/public/promotion-channels/runtime/bewbmr9k")
                        .header("X-Forwarded-Host", "go.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.templateCode").value("DATE_V2"))
                .andExpect(jsonPath("$.data.themeColor").value("#e11d48"))
                .andExpect(jsonPath("$.data.showAppDownload").value(true))
                .andExpect(jsonPath("$.data.targetCountry").value("MIXED"))
                .andExpect(jsonPath("$.data.preselectedCountry").value("IN"))
                .andExpect(jsonPath("$.data.channelName").doesNotExist())
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist());

        verify(service).runtime("bewbmr9k", "go.example.com");
    }
}
