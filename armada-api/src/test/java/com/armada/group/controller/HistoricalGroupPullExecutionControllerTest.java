package com.armada.group.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.service.HistoricalGroupPullExecutionService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 历史群拉人账号组维度 HTTP 合同测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupPullExecutionControllerTest {

    @Mock
    private HistoricalGroupPullExecutionService executionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HistoricalGroupPullExecutionController(executionService))
                .build();
    }

    @Test
    void createBindsSourceAccountGroupInsteadOfOperationAccount() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "members.txt", "text/plain", "8613800000000".getBytes());

        mockMvc.perform(multipart("/api/historical-group-pull-executions")
                        .file(file)
                        .param("sourceAccountGroupId", "12")
                        .param("groupJid", "120363target@g.us")
                        .param("pullerAccountGroupId", "13")
                        .param("singleAddCount", "25")
                        .param("idempotencyKey", "history-source-group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(executionService).create(argThat(request ->
                        request.sourceAccountGroupId().equals(12L)
                                && request.groupJid().equals("120363target@g.us")
                                && request.pullerAccountGroupId().equals(13L)
                                && request.singleAddCount().equals(25)
                                && request.idempotencyKey().equals("history-source-group")),
                eq(file));
    }

    @Test
    void latestUsesSourceAccountGroupAndGroupJid() throws Exception {
        when(executionService.latest(12L, "120363target@g.us"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/historical-group-pull-executions/latest")
                        .param("sourceAccountGroupId", "12")
                        .param("groupJid", "120363target@g.us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(executionService).latest(12L, "120363target@g.us");
    }
}
