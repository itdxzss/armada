package com.armada.promotion.pairing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.promotion.pairing.model.command.PromotionPairingCreateCommand;
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
        when(service.create(any(PromotionPairingCreateCommand.class)))
                .thenReturn(new PromotionPairingCreatedVO(
                        "session-token-once", "REQUESTING", 1_800_000_000_000L));

        mockMvc.perform(post("/api/public/promotion-channels/bewbmr9k/pairing-sessions")
                        .header("X-Forwarded-Host", "go.example.com")
                        .header("X-Real-IP", "203.0.113.10")
                        .header("User-Agent", "Armada-Test/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"919876543210","fbp":"fb.1.1.browser",
                                 "fbc":"fb.1.1.click","sourceUrl":"https://go.example.com/bewbmr9k"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionToken").value("session-token-once"))
                .andExpect(jsonPath("$.data.status").value("REQUESTING"));

        var commandCaptor = org.mockito.ArgumentCaptor.forClass(PromotionPairingCreateCommand.class);
        verify(service).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().channelCode()).isEqualTo("bewbmr9k");
        assertThat(commandCaptor.getValue().forwardedHost()).isEqualTo("go.example.com");
        assertThat(commandCaptor.getValue().clientIp()).isEqualTo("203.0.113.10");
        assertThat(commandCaptor.getValue().clientUserAgent()).isEqualTo("Armada-Test/1.0");
        assertThat(commandCaptor.getValue().fbp()).isEqualTo("fb.1.1.browser");
        assertThat(commandCaptor.getValue().fbc()).isEqualTo("fb.1.1.click");
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

    @Test
    void createIgnoresForwardedClientIpFromAnUntrustedDirectCaller() throws Exception {
        when(service.create(any(PromotionPairingCreateCommand.class)))
                .thenReturn(new PromotionPairingCreatedVO(
                        "session-token-once", "REQUESTING", 1_800_000_000_000L));

        mockMvc.perform(post("/api/public/promotion-channels/bewbmr9k/pairing-sessions")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.20");
                            return request;
                        })
                        .header("X-Forwarded-Host", "go.example.com")
                        .header("X-Real-IP", "203.0.113.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"919876543210\"}"))
                .andExpect(status().isOk());

        var commandCaptor = org.mockito.ArgumentCaptor.forClass(PromotionPairingCreateCommand.class);
        verify(service).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().clientIp()).isEqualTo("198.51.100.20");
    }
}
