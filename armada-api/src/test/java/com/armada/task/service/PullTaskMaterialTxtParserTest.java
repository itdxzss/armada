package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.task.service.PullTaskMaterialTxtParser.ParseResult;
import org.junit.jupiter.api.Test;

/** 普通群链接任务 TXT 料子解析器测试。 */
class PullTaskMaterialTxtParserTest {

    private final PullTaskMaterialTxtParser parser = new PullTaskMaterialTxtParser();

    @Test
    void keepsFirstOccurrenceOrderAndPhysicalLineNumbers() {
        // 第 2 行空行必须被忽略且不占行号，第 3 行的物理行号仍是 3。
        ParseResult result = parser.parse("a.txt", "8613800138001\n\n8613800138002\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::memberSeq,
                        PullTaskMaterialTxtParser.ParsedMember::sourceLineNo,
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1, "8613800138001"),
                        org.assertj.core.groups.Tuple.tuple(2, 3, "8613800138002"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.invalidLineCount()).isZero();
        assertThat(result.duplicateLineCount()).isZero();
    }

    @Test
    void stripsAdminMarkerAndMarksAdminRequired() {
        ParseResult result = parser.parse("a.txt", "8613800138001A\n8613800138002a\n8613800138003\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone,
                        PullTaskMaterialTxtParser.ParsedMember::adminRequired)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("8613800138001", true),
                        org.assertj.core.groups.Tuple.tuple("8613800138002", true),
                        org.assertj.core.groups.Tuple.tuple("8613800138003", false));
    }

    @Test
    void promotesFirstRecordWhenAnyDuplicateCarriesAdminMarker() {
        // 首次出现是普通号，后续重复行带 A：唯一记录必须被提升为需设管理员。
        ParseResult result = parser.parse("a.txt", "8613800138001\n8613800138001A\n");

        assertThat(result.members()).hasSize(1);
        assertThat(result.members().get(0).sourceLineNo()).isEqualTo(1);
        assertThat(result.members().get(0).adminRequired()).isTrue();
        assertThat(result.duplicateLineCount()).isEqualTo(1);
    }

    @Test
    void removesDisplayCharactersBeforeValidating() {
        ParseResult result = parser.parse("a.txt", "+86 138-0013-8001\n(86)13800138002\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly("8613800138001", "8613800138002");
    }

    @Test
    void acceptsSevenAndFifteenDigitsAndRejectsOutsideRange() {
        ParseResult result = parser.parse("a.txt", "1234567\n123456789012345\n123456\n1234567890123456\n");

        assertThat(result.members()).extracting(
                        PullTaskMaterialTxtParser.ParsedMember::normalizedPhone)
                .containsExactly("1234567", "123456789012345");
        assertThat(result.errors()).extracting(PullTaskMaterialTxtParser.LineError::lineNo)
                .containsExactly(3, 4);
        assertThat(result.invalidLineCount()).isEqualTo(2);
    }

    @Test
    void rejectsFullUserJid() {
        ParseResult result = parser.parse("a.txt", "8613800138001@s.whatsapp.net\n");

        assertThat(result.members()).isEmpty();
        assertThat(result.errors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.lineNo()).isEqualTo(1);
                    assertThat(error.reason()).contains("手机号");
                });
    }

    @Test
    void reportsTotalPhysicalLineCountIgnoringTrailingNewline() {
        assertThat(parser.parse("a.txt", "8613800138001\n8613800138002\n").totalLineCount()).isEqualTo(2);
        assertThat(parser.parse("a.txt", "8613800138001\n8613800138002").totalLineCount()).isEqualTo(2);
        assertThat(parser.parse("a.txt", "").totalLineCount()).isZero();
    }

    @Test
    void rejectsFileExceedingMaxLineCount() {
        String content = "8613800138001\n".repeat(20001);

        assertThatThrownBy(() -> parser.parse("big.txt", content))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("20000");
    }
}
