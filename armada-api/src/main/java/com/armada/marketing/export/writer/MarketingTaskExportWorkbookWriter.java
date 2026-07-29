package com.armada.marketing.export.writer;

import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskSummaryExportRow;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** 以流式 XLSX 写出普通营销任务导出文件，避免大数据量在 JVM 中形成完整工作簿。 */
@Component
public class MarketingTaskExportWorkbookWriter {

    /** 数据库流式结果向工作簿逐行推送的边界。 */
    @FunctionalInterface
    public interface RowSource<T> {
        void forEach(Consumer<T> consumer);
    }

    /** 工作簿实际写入的数据行数。 */
    public record WriteResult(int summaryRowCount, int detailRowCount) {
    }

    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final int XLSX_MAX_ROWS = 1_048_576;
    private static final int MAX_DATA_ROWS_PER_SHEET = XLSX_MAX_ROWS - 1;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BUSINESS_ZONE);

    private static final List<String> COUNTRY_HEADERS = List.of(
            "进群时间", "任务ID", "任务名称", "国家/地区", "国家区号", "实际进群号码",
            "群名称", "群组ID", "群状态", "发言权限", "发送账号", "营销条数");

    private static final List<String> SUMMARY_HEADERS = List.of(
            "任务创建时间", "任务开始时间", "任务结束时间", "任务ID", "任务名称",
            "营销群组总计", "正常群组数量", "封禁群组数量", "已解散群组数量",
            "账号已被移出群组数量", "无发言权限群组数量", "营销账号总数", "在线账号数量",
            "异常账号数量", "计划发送总条数", "成功发送总条数", "失败发送总条数",
            "结果未知总条数", "任务状态", "数据统计截止时间", "文件导出时间");

    private static final List<String> GROUP_HEADERS = List.of(
            "加入任务时间", "任务ID", "任务名称", "群名称", "群组ID", "群状态", "发言权限",
            "群人数", "累计成功进群号码数量", "计划发送条数", "成功发送条数", "失败发送条数",
            "结果未知条数", "发送账号", "账号状态", "首次发送时间", "最后发送时间",
            "发送状态", "失败原因", "备注");

    /** 写出按国家成功进群明细工作簿。 */
    public WriteResult writeCountryEntry(Path output,
                                         List<MarketingTaskCountryEntryExportRow> rows,
                                         Instant snapshotAt,
                                         Instant generatedAt) throws IOException {
        return writeCountryEntry(output, consumer -> safe(rows).forEach(consumer), snapshotAt, generatedAt);
    }

    /** 逐行写出按国家成功进群明细，内存中不保留完整结果集。 */
    public WriteResult writeCountryEntry(Path output,
                                         RowSource<MarketingTaskCountryEntryExportRow> rows,
                                         Instant snapshotAt,
                                         Instant generatedAt) throws IOException {
        try (SXSSFWorkbook workbook = workbook(); OutputStream stream = Files.newOutputStream(output)) {
            int detailCount = writeSplitSheets(
                    workbook, "按国家进群明细", COUNTRY_HEADERS, rows, row -> List.of(
                    time(row.getJoinedAt()), value(row.getTaskId()), text(row.getTaskName()),
                    text(row.getCountryName()), text(row.getCountryPhonePrefix()), digits(row.getActualPhone()),
                    text(row.getGroupName()), text(row.getGroupJid()), text(row.getGroupStatus()),
                    text(row.getSpeechPermission()), digits(row.getSenderPhone()), number(row.getMarketingCount())));
            workbook.write(stream);
            return new WriteResult(0, detailCount);
        }
    }

    /** 写出全量任务汇总和群组明细；无明细时仍保留表头工作表。 */
    public WriteResult writeFull(Path output,
                                 List<MarketingTaskSummaryExportRow> summaries,
                                 List<MarketingTaskGroupExportRow> groups,
                                 Instant snapshotAt,
                                 Instant generatedAt) throws IOException {
        return writeFull(output, summaries, consumer -> safe(groups).forEach(consumer), snapshotAt, generatedAt);
    }

    /** 任务汇总为有界小结果集，群组明细由数据库逐行推送。 */
    public WriteResult writeFull(Path output,
                                 List<MarketingTaskSummaryExportRow> summaries,
                                 RowSource<MarketingTaskGroupExportRow> groups,
                                 Instant snapshotAt,
                                 Instant generatedAt) throws IOException {
        String snapshotText = TIME_FORMAT.format(snapshotAt);
        String generatedText = TIME_FORMAT.format(generatedAt);
        try (SXSSFWorkbook workbook = workbook(); OutputStream stream = Files.newOutputStream(output)) {
            RowSource<MarketingTaskSummaryExportRow> summaryRows =
                    consumer -> summariesWithTotal(summaries).forEach(consumer);
            int summaryCount = writeSplitSheets(
                    workbook, "营销任务汇总", SUMMARY_HEADERS,
                    summaryRows, row -> List.of(
                    time(row.getTaskCreatedAt()), time(row.getTaskStartedAt()), time(row.getTaskFinishedAt()),
                    value(row.getTaskId()), text(row.getTaskName()), number(row.getTotalGroupCount()),
                    number(row.getNormalGroupCount()), number(row.getBannedGroupCount()),
                    number(row.getDissolvedGroupCount()), number(row.getKickedGroupCount()),
                    number(row.getNoPermissionGroupCount()), number(row.getTotalAccountCount()),
                    number(row.getOnlineAccountCount()), number(row.getAbnormalAccountCount()),
                    number(row.getPlannedCount()), number(row.getSuccessCount()), number(row.getFailedCount()),
                    number(row.getUnknownCount()), text(row.getTaskStatus()), snapshotText, generatedText));
            int detailCount = writeSplitSheets(workbook, "群组明细", GROUP_HEADERS, groups, row -> List.of(
                    time(row.getJoinedTaskAt()), value(row.getTaskId()), text(row.getTaskName()),
                    text(row.getGroupName()), text(row.getGroupJid()), text(row.getGroupStatus()),
                    text(row.getSpeechPermission()), nullableNumber(row.getGroupMemberCount()),
                    number(row.getJoinedPhoneCount()), number(row.getPlannedCount()),
                    number(row.getSuccessCount()), number(row.getFailedCount()), number(row.getUnknownCount()),
                    digits(row.getSenderPhone()), text(row.getAccountStatus()), time(row.getFirstSentAt()),
                    time(row.getLastSentAt()), text(row.getSendStatus()), text(row.getFailureReason()),
                    text(row.getRemark())));
            workbook.write(stream);
            return new WriteResult(summaryCount, detailCount);
        }
    }

    private static SXSSFWorkbook workbook() {
        SXSSFWorkbook workbook = new SXSSFWorkbook(500);
        workbook.setCompressTempFiles(true);
        return workbook;
    }

    private static <T> int writeSplitSheets(SXSSFWorkbook workbook,
                                            String baseName,
                                            List<String> headers,
                                            RowSource<T> rows,
                                            Function<T, List<Object>> converter) {
        StreamingSheetWriter<T> writer = new StreamingSheetWriter<>(
                workbook, baseName, headers, converter);
        rows.forEach(writer);
        writer.finish();
        return writer.totalRows();
    }

    private static final class StreamingSheetWriter<T> implements Consumer<T> {
        private final SXSSFWorkbook workbook;
        private final String baseName;
        private final List<String> headers;
        private final Function<T, List<Object>> converter;
        private final CellStyle headerStyle;
        private Sheet sheet;
        private int sheetIndex;
        private int rowIndex;
        private int totalRows;

        private StreamingSheetWriter(SXSSFWorkbook workbook,
                                     String baseName,
                                     List<String> headers,
                                     Function<T, List<Object>> converter) {
            this.workbook = workbook;
            this.baseName = baseName;
            this.headers = headers;
            this.converter = converter;
            this.headerStyle = headerStyle(workbook);
            openSheet();
        }

        @Override
        public void accept(T value) {
            if (rowIndex > MAX_DATA_ROWS_PER_SHEET) {
                finishSheet();
                openSheet();
            }
            writeRow(sheet.createRow(rowIndex++), converter.apply(value));
            totalRows++;
        }

        private void openSheet() {
            String sheetName = sheetIndex == 0 ? baseName : baseName + "_" + (sheetIndex + 1);
            sheetIndex++;
            sheet = workbook.createSheet(sheetName);
            writeHeader(sheet, headers, headerStyle);
            rowIndex = 1;
            sheet.createFreezePane(0, 1);
            for (int column = 0; column < headers.size(); column++) {
                sheet.setColumnWidth(
                        column,
                        Math.min(42, Math.max(14, headers.get(column).length() * 3)) * 256);
            }
        }

        private void finish() {
            finishSheet();
        }

        private void finishSheet() {
            sheet.setAutoFilter(new CellRangeAddress(
                    0, Math.max(0, rowIndex - 1), 0, headers.size() - 1));
        }

        private int totalRows() {
            return totalRows;
        }
    }

    private static void writeHeader(Sheet sheet, List<String> headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(style);
        }
    }

    private static void writeRow(Row row, List<Object> values) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            Object value = values.get(index);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(value == null ? "" : String.valueOf(value));
            }
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static String time(Long epochMillis) {
        return epochMillis == null ? "" : TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static String digits(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String value(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static Object nullableNumber(Integer value) {
        return value == null ? "" : Math.max(0, value);
    }

    private static List<MarketingTaskSummaryExportRow> summariesWithTotal(
            List<MarketingTaskSummaryExportRow> summaries) {
        List<MarketingTaskSummaryExportRow> rows = safe(summaries);
        if (rows.size() <= 1) {
            return rows;
        }
        List<MarketingTaskSummaryExportRow> result = new ArrayList<>(rows);
        result.add(totalSummary(rows));
        return result;
    }

    private static MarketingTaskSummaryExportRow totalSummary(List<MarketingTaskSummaryExportRow> rows) {
        MarketingTaskSummaryExportRow total = new MarketingTaskSummaryExportRow();
        total.setTaskName("合计");
        total.setTotalGroupCount(sum(rows, MarketingTaskSummaryExportRow::getTotalGroupCount));
        total.setNormalGroupCount(sum(rows, MarketingTaskSummaryExportRow::getNormalGroupCount));
        total.setBannedGroupCount(sum(rows, MarketingTaskSummaryExportRow::getBannedGroupCount));
        total.setDissolvedGroupCount(sum(rows, MarketingTaskSummaryExportRow::getDissolvedGroupCount));
        total.setKickedGroupCount(sum(rows, MarketingTaskSummaryExportRow::getKickedGroupCount));
        total.setNoPermissionGroupCount(sum(rows, MarketingTaskSummaryExportRow::getNoPermissionGroupCount));
        total.setTotalAccountCount(sum(rows, MarketingTaskSummaryExportRow::getTotalAccountCount));
        total.setOnlineAccountCount(sum(rows, MarketingTaskSummaryExportRow::getOnlineAccountCount));
        total.setAbnormalAccountCount(sum(rows, MarketingTaskSummaryExportRow::getAbnormalAccountCount));
        total.setPlannedCount(sum(rows, MarketingTaskSummaryExportRow::getPlannedCount));
        total.setSuccessCount(sum(rows, MarketingTaskSummaryExportRow::getSuccessCount));
        total.setFailedCount(sum(rows, MarketingTaskSummaryExportRow::getFailedCount));
        total.setUnknownCount(sum(rows, MarketingTaskSummaryExportRow::getUnknownCount));
        return total;
    }

    private static int sum(List<MarketingTaskSummaryExportRow> rows,
                           Function<MarketingTaskSummaryExportRow, Integer> getter) {
        return rows.stream().map(getter).mapToInt(MarketingTaskExportWorkbookWriter::number).sum();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
