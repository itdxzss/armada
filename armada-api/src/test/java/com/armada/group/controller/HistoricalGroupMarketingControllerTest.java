package com.armada.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.dto.HistoricalGroupMarketingSendDTO;
import com.armada.group.service.HistoricalGroupMarketingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 历史群全部营销账号发送端点契约测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupMarketingControllerTest {

    @Mock
    private HistoricalGroupMarketingService marketingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HistoricalGroupMarketingController(marketingService))
                .build();
    }

    @Test
    void marketingSendUsesFrontendPathAndTemplateBody() throws Exception {
        mockMvc.perform(post("/api/historical-group-pull-executions/91/marketing-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketingTemplateId":33}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<HistoricalGroupMarketingSendDTO> request =
                ArgumentCaptor.forClass(HistoricalGroupMarketingSendDTO.class);
        verify(marketingService).send(eq(91L), request.capture());
        assertThat(request.getValue().marketingTemplateId()).isEqualTo(33L);
    }
}
