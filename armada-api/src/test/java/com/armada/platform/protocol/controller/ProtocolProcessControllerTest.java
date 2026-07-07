package com.armada.platform.protocol.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.platform.protocol.process.ProtocolProcessRestartService;
import com.armada.platform.protocol.process.ProtocolRestartProcessVO;
import com.armada.platform.protocol.process.ProtocolRestartVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProtocolProcessControllerTest {

    @Mock
    private ProtocolProcessRestartService restartService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProtocolProcessController(restartService))
                .build();
    }

    @Test
    void postRestart_delegatesToRestartServiceAndReturnsApiResponse() throws Exception {
        ProtocolRestartVO vo = new ProtocolRestartVO(
                true,
                "pm2 restart protocol-master protocol-worker-1 --update-env",
                1_783_420_000_000L,
                1_783_420_002_000L,
                2_000L,
                List.of(new ProtocolRestartProcessVO(
                        "protocol-master",
                        "http://127.0.0.1:8080/readyz",
                        true,
                        200,
                        null,
                        1_783_420_001_000L)),
                "协议进程已重启");
        when(restartService.restart()).thenReturn(vo);

        mockMvc.perform(post("/api/protocol/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.command").value("pm2 restart protocol-master protocol-worker-1 --update-env"))
                .andExpect(jsonPath("$.data.processes[0].processName").value("protocol-master"))
                .andExpect(jsonPath("$.data.processes[0].ready").value(true))
                .andExpect(jsonPath("$.data.message").value("协议进程已重启"));

        verify(restartService).restart();
    }
}
