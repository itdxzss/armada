package com.armada.promotion.channel.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PromotionChannelControllerTest {

    @Mock
    private PromotionChannelService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PromotionChannelController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createAcceptsFacebookFieldAliasesAndReturnsChannel() throws Exception {
        when(service.create(any())).thenReturn(channel());

        mockMvc.perform(post("/api/promotion-channels/create")
                        .contentType("application/json")
                        .content("""
                                {
                                  "channelName":"印度渠道",
                                  "ownerUserId":20001,
                                  "targetCountry":"IN",
                                  "landingTemplateId":11,
                                  "domain":"go.example.com",
                                  "preselectedCountry":"IN",
                                  "platform":1,
                                  "fbPixelId":"pixel-123",
                                  "fbAccessToken":"token-abc",
                                  "inAppOpenAllowed":true,
                                  "marketingAllowed":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.channelCode").value("a8k2m9qx"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        verify(service).create(org.mockito.ArgumentMatchers.argThat(request ->
                "pixel-123".equals(request.trackingId())
                        && "token-abc".equals(request.accessToken())
                        && "IN".equals(request.targetCountry())
                        && "IN".equals(request.preselectedCountry())
                        && request.ownerUserId().equals(20001L)));
    }

    @Test
    void pageBindsCreatorAndRepeatedOwnerIdsWithoutTenantParameter() throws Exception {
        when(service.page(any())).thenReturn(PageResult.of(List.of(channel()), 1, 100, 1));

        mockMvc.perform(get("/api/promotion-channels/query")
                        .param("targetCountry", "IN")
                        .param("landingTemplateId", "11")
                        .param("creatorUserId", "20001")
                        .param("ownerUserIds", "20001", "20002")
                        .param("page", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].creatorUserId").value(20001));

        ArgumentCaptor<PromotionChannelQuery> captor = ArgumentCaptor.forClass(PromotionChannelQuery.class);
        verify(service).page(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTargetCountry()).isEqualTo("IN");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCreatorUserId()).isEqualTo(20001L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getOwnerUserIds())
                .containsExactly(20001L, 20002L);
    }

    @Test
    void updateBindsEditableFieldsAndTikTokAliasesWithoutTenantParameter() throws Exception {
        mockMvc.perform(put("/api/promotion-channels/51")
                        .contentType("application/json")
                        .content("""
                                {
                                  "channelName":"TikTok渠道",
                                  "ownerUserId":20002,
                                  "targetCountry":"MIXED",
                                  "landingTemplateId":11,
                                  "domain":"new.example.com",
                                  "preselectedCountry":"IN",
                                  "platform":2,
                                  "tiktokPixelId":"pixel-new",
                                  "tiktokAccessToken":"token-new",
                                  "inAppOpenAllowed":true,
                                  "marketingAllowed":false,
                                  "status":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).update(org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "pixel-new".equals(request.trackingId())
                                && "token-new".equals(request.accessToken())
                                && request.ownerUserId().equals(20002L)
                                && request.status().equals(0)));
    }

    @Test
    void deleteDelegatesPathIdAndReturnsUnifiedSuccess() throws Exception {
        mockMvc.perform(delete("/api/promotion-channels/51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).delete(51L);
    }

    private static PromotionChannelVO channel() {
        return new PromotionChannelVO(
                51L, "印度渠道", "a8k2m9qx", 20001L, 20001L,
                "IN", "IN", "印度", "flag", false,
                11L, "基础领奖", 1, "Facebook", "UNPROBED",
                "https://go.example.com/a8k2m9qx",
                "https://go.example.com/a8k2m9qx/1",
                "IN", "IN", "印度", "+91", "flag",
                1, true, true, 1784217600000L);
    }
}
