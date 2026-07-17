package com.armada.group.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.idev.excel.FastExcel;
import cn.idev.excel.support.ExcelTypeEnum;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

/** 历史群料子解析规则测试。 */
class HistoricalGroupMaterialParserTest {

    private final HistoricalGroupMaterialParser parser =
            new HistoricalGroupMaterialParser(new FileLinesExtractor());

    @ParameterizedTest(name = "支持 {0}")
    @MethodSource("supportedFiles")
    void supportsTxtCsvXlsxAndXls(String ignoredFormat, MockMultipartFile file) {
        HistoricalGroupMaterialParser.ParseResult result = parser.parse(file);

        assertThat(result.members()).extracting(HistoricalGroupMaterialParser.ParsedMember::phone)
                .containsExactly("8613800000001", "8613800000002");
        assertThat(result.marketingCount()).isEqualTo(1);
        assertThat(result.normalCount()).isEqualTo(1);
    }

    @Test
    void normalizesMarkerAndPunctuationPromotesMarketingAndKeepsFirstLine() {
        String content = """
                +86 (138) 0000-0001
                +86-139-0000-0002A
                8613800000001a
                not-a-phone
                86 139 0000 0002 A
                +86 137 0000 0003
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "material.txt", "text/plain", content.getBytes(UTF_8));

        HistoricalGroupMaterialParser.ParseResult result = parser.parse(file);

        assertThat(result.members()).extracting(HistoricalGroupMaterialParser.ParsedMember::phone)
                .containsExactly("8613800000001", "8613900000002", "8613700000003");
        assertThat(result.members()).extracting(HistoricalGroupMaterialParser.ParsedMember::materialType)
                .containsExactly(
                        HistoricalGroupMaterialType.MARKETING,
                        HistoricalGroupMaterialType.MARKETING,
                        HistoricalGroupMaterialType.NORMAL);
        assertThat(result.members()).extracting(HistoricalGroupMaterialParser.ParsedMember::lineNo)
                .containsExactly(1, 2, 6);
        assertThat(result.marketingCount()).isEqualTo(2);
        assertThat(result.normalCount()).isEqualTo(1);
        assertThat(result.invalidCount()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(2);
    }

    private static Stream<Arguments> supportedFiles() {
        return Stream.of(
                Arguments.of("TXT", textFile("material.txt", "8613800000001A\n8613800000002")),
                Arguments.of("CSV", textFile("material.csv", "8613800000001A,营销\n8613800000002,普通")),
                Arguments.of("XLSX", excelFile("material.xlsx", ExcelTypeEnum.XLSX)),
                Arguments.of("XLS", excelFile("material.xls", ExcelTypeEnum.XLS)));
    }

    private static MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(UTF_8));
    }

    private static MockMultipartFile excelFile(String name, ExcelTypeEnum type) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FastExcel.write(output).excelType(type).sheet().doWrite(List.of(
                List.of("8613800000001A", "营销"),
                List.of("8613800000002", "普通")));
        return new MockMultipartFile("file", name, "application/octet-stream", output.toByteArray());
    }
}
