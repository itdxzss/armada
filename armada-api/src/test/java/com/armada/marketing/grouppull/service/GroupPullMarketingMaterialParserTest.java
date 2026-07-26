package com.armada.marketing.grouppull.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.service.FileLinesExtractor;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 拉群营销料子单文件解析规则测试。 */
class GroupPullMarketingMaterialParserTest {

    private final GroupPullMarketingMaterialParser parser =
            new GroupPullMarketingMaterialParser(new FileLinesExtractor());

    @Test
    void txtNormalizesDeduplicatesAndKeepsFirstOrder() {
        MockMultipartFile file = file(
                "numbers.txt",
                "+86 138-0000-0001\n8613800000001\n(91) 98765 43210\nabc");

        assertThat(parser.parse(file))
                .extracting(GroupPullMarketingMaterialParser.ParsedMaterial::phone)
                .containsExactly("8613800000001", "919876543210");
    }

    @Test
    void csvReadsOnlyFirstColumn() {
        MockMultipartFile file = file(
                "numbers.csv",
                "8613800000001,name-a\n8613800000002,name-b");

        assertThat(parser.parse(file))
                .extracting(GroupPullMarketingMaterialParser.ParsedMaterial::phone)
                .containsExactly("8613800000001", "8613800000002");
    }

    @Test
    void invalidRowsDoNotCreateSequenceGaps() {
        MockMultipartFile file = file(
                "numbers.txt",
                "abc\n8613800000001\n8613800000001\na@b.com\n8613800000002");

        assertThat(parser.parse(file))
                .extracting(GroupPullMarketingMaterialParser.ParsedMaterial::lineNo)
                .containsExactly(1, 2);
    }

    @Test
    void rejectsUnsupportedOrEmptyFile() {
        assertThatThrownBy(() -> parser.parse(file("a.xlsx", "8613800000001")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse(file("a.txt", "abc")))
                .isInstanceOf(BusinessException.class);
    }

    private static MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile("materialFile", filename, "text/plain", content.getBytes(UTF_8));
    }
}
