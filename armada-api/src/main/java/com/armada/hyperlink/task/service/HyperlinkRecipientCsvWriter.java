package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 收信人流水 CSV 的固定八列写入器。 */
public class HyperlinkRecipientCsvWriter {

    public static final String CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final String HEADER = "收信号码,收信国家,发送账号,发信国家,状态,失败码,失败原因,状态时间";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public void writeHeader(Writer writer) throws IOException {
        writer.write('\ufeff');
        writer.write(HEADER);
        writer.write("\r\n");
    }

    public void writeRow(Writer writer, HyperlinkRecipientRow row) throws IOException {
        writeCells(writer,
                excelPhone(row.getRecipientPhone()),
                row.getRecipientCountryIso2(),
                row.getSenderPhone() == null ? "未分配" : excelPhone(row.getSenderPhone()),
                row.getSenderCountryIso2(),
                statusText(HyperlinkRecipientStatus.fromCode(row.getStatusCode())),
                row.getFailCode(),
                row.getFailReason(),
                formatTime(row.getStatusAt()));
    }

    private static void writeCells(Writer writer, String... cells) throws IOException {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escape(cells[index]));
        }
        writer.write("\r\n");
    }

    private static String excelPhone(String value) {
        return value == null || value.isBlank() ? "" : "=\"" + value + "\"";
    }

    private static String formatTime(Long epochMillis) {
        return epochMillis == null ? "" : TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String statusText(HyperlinkRecipientStatus status) {
        return switch (status) {
            case PENDING -> "待发送";
            case SENDING -> "发送中";
            case SUCCESS -> "单钩";
            case DELIVERED -> "双钩";
            case READ -> "已读";
            case FAILED, UNREGISTERED -> "失败";
        };
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0 && safe.indexOf('"') < 0
                && safe.indexOf('\r') < 0 && safe.indexOf('\n') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
