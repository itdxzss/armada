package com.armada.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.service.GroupLinkImportService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GroupLinkImportController 单测:覆盖 CSV 转义工具函数 escapeCsvRow。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkImportControllerTest {

    @Mock
    private GroupLinkImportService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GroupLinkImportController(service))
                .build();
    }

    @Test
    void exportFailed_acceptsCamelCaseQueryParams() throws Exception {
        when(service.exportFailed(7L, 9L)).thenReturn(List.of());

        mockMvc.perform(get("/api/group-link-imports/failed/export")
                        .param("labelId", "7")
                        .param("batchId", "9"))
                .andExpect(status().isOk());

        verify(service).exportFailed(7L, 9L);
    }

    @Test
    void escapeCsvRow_plainFields_noQuotes() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"1", "群A", "chat.whatsapp.com/Abc", "格式错误", "2024-06-01 20:00:00"});
        assertThat(line).isEqualTo("1,群A,chat.whatsapp.com/Abc,格式错误,2024-06-01 20:00:00");
    }

    @Test
    void escapeCsvRow_fieldWithComma_wrapsInQuotes() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"1", "含,逗号群", "chat.whatsapp.com/Abc", "", ""});
        assertThat(line).isEqualTo("1,\"含,逗号群\",chat.whatsapp.com/Abc,,");
    }

    @Test
    void escapeCsvRow_fieldWithDoubleQuote_escapesAsDoubleDoubleQuote() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"2", "群\"名\"", "chat.whatsapp.com/X", "", ""});
        assertThat(line).isEqualTo("2,\"群\"\"名\"\"\",chat.whatsapp.com/X,,");
    }

    @Test
    void escapeCsvRow_fieldWithNewline_wrapsInQuotes() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"3", "群\n名", "", "", ""});
        assertThat(line).isEqualTo("3,\"群\n名\",,,");
    }

    @Test
    void escapeCsvRow_fieldWithCarriageReturn_wrapsInQuotes() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"4", "群\r名", "", "", ""});
        assertThat(line).isEqualTo("4,\"群\r名\",,,");
    }

    @Test
    void escapeCsvRow_nullField_treatedAsEmpty() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"1", null, null, null, null});
        assertThat(line).isEqualTo("1,,,,");
    }

    @Test
    void escapeCsvRow_headerRow_returnsHeaderLine() {
        String line = GroupLinkImportController.escapeCsvRow(
                new String[]{"行号", "群名称", "群链接", "失败原因", "导入时间"});
        assertThat(line).isEqualTo("行号,群名称,群链接,失败原因,导入时间");
    }
}
