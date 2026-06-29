package com.armada.group.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkPreviewItemVO;
import com.armada.group.service.FileLinesExtractor;
import com.armada.group.service.GroupLinkImportService;
import com.armada.group.service.GroupLinkService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GroupLinkController 单测:只覆盖轻量路由委托,不启动数据库或协议层。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkControllerTest {

    @Mock
    private GroupLinkService groupLinkService;

    @Mock
    private GroupLinkImportService importService;

    @Mock
    private FileLinesExtractor extractor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GroupLinkController(groupLinkService, importService, extractor))
                .build();
    }

    @Test
    void postBatchPreview_delegatesToServiceAndReturnsApiResponse() throws Exception {
        GroupLinkPreviewBatchVO vo = new GroupLinkPreviewBatchVO(
                1,
                1,
                0,
                List.of(new GroupLinkPreviewItemVO(
                        10L,
                        "https://chat.whatsapp.com/ABC123",
                        true,
                        null,
                        "120363preview@g.us",
                        "预览群",
                        12,
                        false,
                        "8613999999999",
                        true,
                        "ABC123",
                        1_780_387_200_000L)));
        when(groupLinkService.previewBatch(argThat(dto ->
                dto != null && dto.accountId().equals(7L) && dto.ids().equals(List.of(10L))))).thenReturn(vo);

        mockMvc.perform(post("/api/group-links/batch-preview")
                        .contentType("application/json")
                        .content("{\"accountId\":7,\"ids\":[10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.items[0].groupLinkId").value(10))
                .andExpect(jsonPath("$.data.items[0].success").value(true))
                .andExpect(jsonPath("$.data.items[0].groupJid").value("120363preview@g.us"))
                .andExpect(jsonPath("$.data.items[0].ownerPhone").value("8613999999999"));

        verify(groupLinkService).previewBatch(argThat(dto ->
                dto != null && dto.accountId().equals(7L) && dto.ids().equals(List.of(10L))));
    }
}
