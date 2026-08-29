package com.armada.hyperlink.task.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** CSV 列顺序、BOM 和未分配展示合同。 */
class HyperlinkAccountStatsCsvWriterTest {

    @Test
    void writesFrozenColumnsAssignedAndUnassignedRows() throws Exception {
        HyperlinkAccountStatsCsvWriter csv = new HyperlinkAccountStatsCsvWriter();
        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) {
            csv.writeHeader(writer);
            csv.writeItem(writer, new HyperlinkAccountStatItemVO(
                    101L, 101L, "551100000101", "BR", "BUSINESS",
                    new BigDecimal("10.0"), 8, 6, 2, 1_000L));
            csv.writeItem(writer, new HyperlinkAccountStatItemVO(
                    0L, null, null, null, null,
                    new BigDecimal("0.0"), 0, 0, 1, null));
        }

        assertThat(output.toString()).startsWith("\ufeff发送账号,发信国家,账号类型,存活天数,"
                + "单钩数,双钩数,失败数,最后发送");
        assertThat(output.toString()).contains("551100000101,BR,商业号,10.0,8,6,2,");
        assertThat(output.toString()).contains("未分配,,,0.0,0,0,1,");
    }
}
