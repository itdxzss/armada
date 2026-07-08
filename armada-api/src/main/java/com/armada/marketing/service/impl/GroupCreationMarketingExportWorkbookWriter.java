package com.armada.marketing.service.impl;

import cn.idev.excel.FastExcel;
import cn.idev.excel.write.handler.RowWriteHandler;
import cn.idev.excel.write.handler.SheetWriteHandler;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteTableHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

/**
 * 建群营销统计 Excel 写入器。
 *
 * <p>导出统计口径固定为:建群人数 = 料子有效号码数 + 群主账号;
 * 进群人数 = 发送前群成员数 - 群主账号。成员数未能读取时保留为空,不强行写 0。</p>
 */
@Component
public class GroupCreationMarketingExportWorkbookWriter {

    /** XLSX 文件 content-type。 */
    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 导出标题和文件名使用的业务时区。 */
    private static final ZoneId EXPORT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 标题行导出时间格式。 */
    private static final DateTimeFormatter TITLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(EXPORT_ZONE);

    /** 文件名导出时间格式。 */
    private static final DateTimeFormatter FILENAME_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(EXPORT_ZONE);

    /**
     * 返回导出文件的 content-type。
     *
     * @return XLSX content-type
     */
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * 生成建群营销统计导出文件名。
     *
     * @param exportedAt 导出时间
     * @return 带导出时间的 XLSX 文件名
     */
    public String filename(Instant exportedAt) {
        return "建群营销统计导出_" + FILENAME_TIME_FORMAT.format(exportedAt) + ".xlsx";
    }

    /**
     * 写入建群营销统计 Excel。
     *
     * @param exportRows 导出数据行
     * @param exportedAt 导出时间
     * @return XLSX 文件二进制内容
     */
    public byte[] write(List<GroupCreationMarketingExportRow> exportRows, Instant exportedAt) {
        List<List<Object>> rows = toWorkbookRows(exportRows, exportedAt);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        FastExcel.write(outputStream)
                .needHead(false)
                .registerWriteHandler(new ExportLayoutHandler(rows.size()))
                .sheet("建群统计")
                .doWrite(rows);
        return outputStream.toByteArray();
    }

    private List<List<Object>> toWorkbookRows(List<GroupCreationMarketingExportRow> exportRows, Instant exportedAt) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("建群统计导出-" + TITLE_TIME_FORMAT.format(exportedAt) + "（导出时间）", "", "", ""));
        rows.add(row("任务ID", "群名称", "建群人数", "进群人数（目标数据人数）"));
        int buildTotal = 0;
        int joinedTotal = 0;
        for (GroupCreationMarketingExportRow exportRow : exportRows) {
            int buildCount = safeInt(exportRow.getParticipantCount()) + 1;
            Integer joinedCount = joinedCount(exportRow.getSendMemberCount());
            buildTotal += buildCount;
            if (joinedCount != null) {
                joinedTotal += joinedCount;
            }
            rows.add(row(
                    String.valueOf(exportRow.getTaskId()),
                    blankToDash(exportRow.getGroupSubject()),
                    buildCount,
                    joinedCount));
        }
        rows.add(row("合计", "", buildTotal, joinedTotal));
        return rows;
    }

    private static List<Object> row(Object first, Object second, Object third, Object fourth) {
        ArrayList<Object> row = new ArrayList<>(4);
        row.add(first);
        row.add(second);
        row.add(third);
        row.add(fourth);
        return row;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private static Integer joinedCount(Integer sendMemberCount) {
        return sendMemberCount == null ? null : Math.max(sendMemberCount - 1, 0);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 建群营销导出表格样式处理器。
     *
     * <p>FastExcel 写出时逐行回调,这里负责合并标题行、设置列宽以及标题/表头/合计样式。</p>
     */
    private static class ExportLayoutHandler implements SheetWriteHandler, RowWriteHandler {
        /** 写入总行数,用于识别合计行。 */
        private final int rowCount;

        /** 标题行样式。 */
        private CellStyle titleStyle;

        /** 表头行样式。 */
        private CellStyle headerStyle;

        /** 合计行样式。 */
        private CellStyle totalStyle;

        /**
         * 创建导出样式处理器。
         *
         * @param rowCount 写入总行数
         */
        ExportLayoutHandler(int rowCount) {
            this.rowCount = rowCount;
        }

        @Override
        public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder, WriteSheetHolder writeSheetHolder) {
            Sheet sheet = writeSheetHolder.getSheet();
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 32 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 24 * 256);
            Workbook workbook = writeWorkbookHolder.getWorkbook();
            titleStyle = titleStyle(workbook);
            headerStyle = headerStyle(workbook);
            totalStyle = totalStyle(workbook);
        }

        @Override
        public void afterRowDispose(WriteSheetHolder writeSheetHolder,
                                    WriteTableHolder writeTableHolder,
                                    Row row,
                                    Integer relativeRowIndex,
                                    Boolean isHead) {
            if (row == null) {
                return;
            }
            if (row.getRowNum() == 0) {
                row.setHeightInPoints(24);
                apply(row, titleStyle);
                return;
            }
            if (row.getRowNum() == 1) {
                apply(row, headerStyle);
                return;
            }
            if (row.getRowNum() == rowCount - 1) {
                apply(row, totalStyle);
            }
        }

        private static void apply(Row row, CellStyle style) {
            for (int index = 0; index < 4; index++) {
                Cell cell = row.getCell(index);
                if (cell == null) {
                    cell = row.createCell(index);
                }
                cell.setCellStyle(style);
            }
        }

        private static CellStyle titleStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            return style;
        }

        private static CellStyle headerStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            return style;
        }

        private static CellStyle totalStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            return style;
        }
    }
}
