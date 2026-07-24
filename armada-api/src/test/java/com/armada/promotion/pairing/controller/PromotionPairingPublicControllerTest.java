package com.armada.promotion.pairing.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.promotion.pairing.model.vo.PromotionPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.PromotionPairingStatusVO;
import com.armada.promotion.pairing.service.PromotionPairingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PromotionPairingPublicControllerTest {

    @Mock
    private PromotionPairingService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PromotionPairingPublicController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createUsesTrustedForwardedHostAndReturnsOneTimeSessionToken() throws Exception {
        when(service.create("bewbmr9k", "go.example.com", "919876543210"))
                .thenReturn(new PromotionPairingCreatedVO(
                        "session-token-once", "REQUESTING", 1_800_000_000_000L));

        mockMvc.perform(post("/api/public/promotion-channels/bewbmr9k/pairing-sessions")
                        .header("X-Forwarded-Host", "go.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"919876543210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionToken").value("session-token-once"))
                .andExpect(jsonPath("$.data.status").value("REQUESTING"));

        verify(service).create("bewbmr9k", "go.example.com", "919876543210");
    }

    @Test
    void statusNeverReturnsCredentialOrProxyMaterial() throws Exception {
        when(service.status("session-token-once")).thenReturn(new PromotionPairingStatusVO(
                "WAITING_CONFIRMATION", "A1B2C3D4", 1_800_000_000_000L, null, null, null));

        mockMvc.perform(get("/api/public/promotion-pairing-sessions/status")
                        .header("X-Pairing-Session-Token", "session-token-once"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.pairingCode").value("A1B2C3D4"))
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.proxy").doesNotExist());
    }
}
