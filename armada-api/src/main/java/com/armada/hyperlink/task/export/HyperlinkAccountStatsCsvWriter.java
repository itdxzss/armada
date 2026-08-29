package com.armada.hyperlink.task.export;

import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** 发信账号统计 UTF-8 CSV 行写入器。 */
@Component
public class HyperlinkAccountStatsCsvWriter {

    public static final String CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public void writeHeader(BufferedWriter writer) throws IOException {
        writer.write('\ufeff');
        writeRow(writer, "发送账号", "发信国家", "账号类型", "存活天数",
                "单钩数", "双钩数", "失败数", "最后发送");
    }

    public void writeItem(BufferedWriter writer, HyperlinkAccountStatItemVO item) throws IOException {
        writeRow(writer,
                item.accountId() == null ? "未分配" : value(item.senderPhone()),
                value(item.senderCountryIso2()),
                accountType(item.accountType()),
                item.retentionDays().toPlainString(),
                Long.toString(item.successNum()),
                Long.toString(item.deliveredNum()),
                Long.toString(item.failedNum()),
                item.lastSendAt() == null ? "" : DATE_TIME.format(Instant.ofEpochMilli(item.lastSendAt())));
    }

    private static String accountType(String value) {
        if ("PERSONAL".equals(value)) {
            return "个人号";
        }
        if ("BUSINESS".equals(value)) {
            return "商业号";
        }
        return "";
    }

    private static void writeRow(BufferedWriter writer, String... values) throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escape(values[index]));
        }
        writer.newLine();
    }

    private static String escape(String value) {
        String normalized = value(value);
        if (normalized.indexOf(',') >= 0 || normalized.indexOf('"') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            return '"' + normalized.replace("\"", "\"\"") + '"';
        }
        return normalized;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
