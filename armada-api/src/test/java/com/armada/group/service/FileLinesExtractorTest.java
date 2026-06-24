package com.armada.group.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.idev.excel.FastExcel;
import com.armada.shared.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * FileLinesExtractor 单测:TXT 分行 / CSV 首列 / Excel 首列 / text+file 合并 / 都 null 返空 / 非法类型报错。
 */
class FileLinesExtractorTest {

    private final FileLinesExtractor extractor = new FileLinesExtractor();

    @Test
    void txtSplitsLines() throws Exception {
        var file = new MockMultipartFile("f", "a.txt", "text/plain", "l1\nl2\n".getBytes(UTF_8));
        assertEquals(List.of("l1", "l2"), extractor.extract(file, null));
    }

    @Test
    void mergesTextAndFile() throws Exception {
        var file = new MockMultipartFile("f", "a.txt", "text/plain", "l1".getBytes(UTF_8));
        assertEquals(List.of("t1", "l1"), extractor.extract(file, "t1"));
    }

    @Test
    void bothNull_returnsEmpty() throws Exception {
        assertTrue(extractor.extract(null, null).isEmpty());
    }

    @Test
    void textOnlyBlank_andNullFile_returnsEmpty() throws Exception {
        assertTrue(extractor.extract(null, "   ").isEmpty());
    }

    @Test
    void csvTakesFirstColumn() throws Exception {
        var csv = "url1,name1\nurl2,name2".getBytes(UTF_8);
        var file = new MockMultipartFile("f", "a.csv", "text/csv", csv);
        assertEquals(List.of("url1", "url2"), extractor.extract(file, null));
    }

    @Test
    void excelReadsFirstColumn() throws Exception {
        // 用 FastExcel 在内存写一个 xlsx,再测 extract 读首列
        var baos = new ByteArrayOutputStream();
        // FastExcel 写 List<List<Object>> 不需要 header
        List<List<Object>> rows = List.of(
                List.of("https://chat.whatsapp.com/Row1", "群名A"),
                List.of("https://chat.whatsapp.com/Row2", "群名B")
        );
        FastExcel.write(baos).sheet().doWrite(rows);
        var file = new MockMultipartFile("f", "links.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                baos.toByteArray());
        List<String> lines = extractor.extract(file, null);
        assertEquals(2, lines.size());
        assertEquals("https://chat.whatsapp.com/Row1", lines.get(0));
        assertEquals("https://chat.whatsapp.com/Row2", lines.get(1));
    }

    @Test
    void unsupportedExtension_throwsBusinessException() {
        var file = new MockMultipartFile("f", "a.pdf", "application/pdf", "data".getBytes(UTF_8));
        assertThrows(BusinessException.class, () -> extractor.extract(file, null));
    }

    @Test
    void txtSkipsBlankLines() throws Exception {
        var content = "line1\n  \nline2\n\nline3".getBytes(UTF_8);
        var file = new MockMultipartFile("f", "a.txt", "text/plain", content);
        assertEquals(List.of("line1", "line2", "line3"), extractor.extract(file, null));
    }
}
