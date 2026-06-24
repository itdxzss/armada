package com.armada.group.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * GroupLinkImportController 单测:覆盖 CSV 转义工具函数 escapeCsvRow。
 */
class GroupLinkImportControllerTest {

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
