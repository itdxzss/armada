package com.armada.marketing.export.writer;

import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
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

    /** 一次数据遍历同时向群统计和成员明细工作表推送，避免缓存完整成员集。 */
    @FunctionalInterface
    public interface FullRowSource {
        void forEach(Consumer<MarketingTaskGroupExportRow> groupConsumer,
                     Consumer<MarketingTaskGroupMemberExportRow> memberConsumer);
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
            "进群时间", "任务 ID", "任务名称", "国家/地区", "国家区号", "实际进群号码",
            "群名称", "群组链接", "群状态", "发言权限", "发送账号", "营销条数");

    private static final List<String> GROUP_HEADERS = List.of(
            "任务 ID", "任务名称", "群名称", "群组链接", "加入任务时间", "群状态", "发言权限",
            "群人数", "累计成功进群号码数量", "计划发送条数", "成功发送条数", "失败发送条数",
            "结果未知条数", "发送账号", "账号状态", "首次发送时间", "最后发送时间", "发送状态",
            "失败原因", "数据统计截止时间", "备注");

    private static final List<String> MEMBER_HEADERS = List.of(
            "任务 ID", "任务名称", "群名称", "群组链接", "群状态", "群人数", "群成员", "角色",
            "国家/地区", "是否在群", "退出方式", "进群时间", "退群时间", "本任务进群状态");

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
                    workbook, "国家进群数据", COUNTRY_HEADERS, rows, row -> List.of(
                    time(row.getJoinedAt()), value(row.getTaskId()), text(row.getTaskName()),
                    text(row.getCountryName()), text(row.getCountryPhonePrefix()), digits(row.getActualPhone()),
                    text(row.getGroupName()), text(row.getGroupLink()), text(row.getGroupStatus()),
                    text(row.getSpeechPermission()), digits(row.getSenderPhone()), number(row.getMarketingCount())));
            workbook.write(stream);
            return new WriteResult(0, detailCount);
        }
    }

    /** 写出营销群组统计和受控群成员明细；无数据时仍保留表头工作表。 */
    public WriteResult writeFull(Path output,
                                 List<MarketingTaskGroupExportRow> groups,
                                 List<MarketingTaskGroupMemberExportRow> members,
                                 Instant snapshotAt,
                                 Instant generatedAt) throws IOException {
        return writeFull(
                output,
                consumer -> safe(groups).forEach(consumer),
                consumer -> safe(members).forEach(consumer),
                snapshotAt,
                generatedAt);
    }

    /** 群组统计和群成员明细均由数据库逐行推送。 */
    public WriteResult writeFull(Path output,
                                 RowSource<MarketingTaskGroupExportRow> groups,
                                 RowSource<MarketingTaskGroupMemberExportRow> members,
                                 Instant snapshotAt,
                                 Instant generatedAt) throws IOException {
        return writeFull(output, (groupConsumer, memberConsumer) -> {
            groups.forEach(groupConsumer);
            members.forEach(memberConsumer);
        }, snapshotAt, generatedAt);
    }

    /** 单次遍历同时逐行写出两个工作表，成员数据不在 JVM 中形成完整集合。 */
    public WriteResult writeFull(Path output,
                                 FullRowSource rows,
                                 Instant snapshotAt,
                                 Instant generatedAt) throws IOException {
        String snapshotText = TIME_FORMAT.format(snapshotAt);
        try (SXSSFWorkbook workbook = workbook(); OutputStream stream = Files.newOutputStream(output)) {
            StreamingSheetWriter<MarketingTaskGroupExportRow> groupWriter = new StreamingSheetWriter<>(
                    workbook, "营销群组统计", GROUP_HEADERS, row -> List.of(
                    value(row.getTaskId()), text(row.getTaskName()), text(row.getGroupName()),
                    text(row.getGroupLink()), time(row.getJoinedTaskAt()), text(row.getGroupStatus()),
                    text(row.getSpeechPermission()), nullableNumber(row.getGroupMemberCount()),
                    number(row.getJoinedPhoneCount()), number(row.getPlannedCount()),
                    number(row.getSuccessCount()), number(row.getFailedCount()), number(row.getUnknownCount()),
                    digits(row.getSenderPhone()), text(row.getAccountStatus()), time(row.getFirstSentAt()),
                    time(row.getLastSentAt()), text(row.getSendStatus()), text(row.getFailureReason()),
                    snapshotText, text(row.getRemark())));
            StreamingSheetWriter<MarketingTaskGroupMemberExportRow> memberWriter = new StreamingSheetWriter<>(
                    workbook, "群组成员明细", MEMBER_HEADERS, row -> List.of(
                    value(row.getTaskId()), text(row.getTaskName()), text(row.getGroupName()),
                    text(row.getGroupLink()), text(row.getGroupStatus()),
                    nullableNumber(row.getGroupMemberCount()), digits(row.getMemberPhone()),
                    text(row.getRole()), text(row.getCountryName()), text(row.getInGroup()),
                    text(row.getExitType()), time(row.getJoinedAt()), time(row.getExitedAt()),
                    text(row.getTaskJoinStatus())));
            rows.forEach(groupWriter, memberWriter);
            groupWriter.finish();
            memberWriter.finish();
            workbook.write(stream);
            return new WriteResult(groupWriter.totalRows(), memberWriter.totalRows());
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

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
