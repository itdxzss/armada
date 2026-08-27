package com.armada.hyperlink.data.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 数据包 TXT 的 UTF-8、BOM、计数和去重合同测试。 */
class DataPackageTxtParserTest {

    private final DataPackageTxtParser parser = new DataPackageTxtParser();

    @Test
    void parse_acceptsBomSkipsBlankLinesAndCountsInvalidAndDuplicateRows() {
        byte[] bytes = ("\uFEFF639171234567\n\n 639171234567 \nabc\n8613800138000\r\n")
                .getBytes(StandardCharsets.UTF_8);

        ParsedDataPackagePhones result = parser.parse(bytes);

        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(result.invalidRows()).isEqualTo(1);
        assertThat(result.duplicatedRows()).isEqualTo(1);
        assertThat(result.uniquePhones()).containsExactly("639171234567", "8613800138000");
    }

    @Test
    void parse_rejectsMoreThanFiveThousandNonEmptyRows() {
        String content = "123456\n".repeat(5_001);

        assertThatThrownBy(() -> parser.parse(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单次最多导入 5000 条");
    }

    @Test
    void parse_rejectsMalformedUtf8() {
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UTF-8");
    }
}
