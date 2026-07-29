package com.armada.marketing.export.writer;

import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskSummaryExportRow;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskExportWorkbookWriterTest {

    @TempDir
    Path tempDir;

    private final MarketingTaskExportWorkbookWriter writer = new MarketingTaskExportWorkbookWriter();

    @Test
    void countryEntryWorkbookContainsOneSheetAndProductHeaders() throws Exception {
        Path file = tempDir.resolve("country.xlsx");

        writer.writeCountryEntry(
                file,
                java.util.List.of(),
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:21:00Z"));

        try (InputStream input = Files.newInputStream(file);
             var workbook = WorkbookFactory.create(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("按国家进群明细");
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 12);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("进群时间");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("实际进群号码");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("营销条数");
        }
    }

    @Test
    void fullWorkbookAlwaysContainsSummaryAndGroupDetailSheets() throws Exception {
        Path file = tempDir.resolve("full.xlsx");

        writer.writeFull(
                file,
                java.util.List.of(),
                java.util.List.of(),
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:21:00Z"));

        try (InputStream input = Files.newInputStream(file);
             var workbook = WorkbookFactory.create(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("营销任务汇总");
            assertThat(workbook.getSheetName(1)).isEqualTo("群组明细");
            assertThat(workbook.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 21);
            assertThat(workbook.getSheetAt(1).getRow(0).getLastCellNum()).isEqualTo((short) 20);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(19).getStringCellValue())
                    .isEqualTo("数据统计截止时间");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(20).getStringCellValue())
                    .isEqualTo("文件导出时间");
        }
    }

    @Test
    void textCellsNeutralizeExcelFormulaInjection() throws Exception {
        Path file = tempDir.resolve("safe.xlsx");
        MarketingTaskCountryEntryExportRow row = new MarketingTaskCountryEntryExportRow();
        row.setTaskName("=HYPERLINK(\"https://invalid.example\")");
        row.setGroupName("+SUM(1,1)");

        writer.writeCountryEntry(
                file,
                java.util.List.of(row),
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:21:00Z"));

        try (InputStream input = Files.newInputStream(file);
             var workbook = WorkbookFactory.create(input)) {
            var data = workbook.getSheetAt(0).getRow(1);
            assertThat(data.getCell(2).getStringCellValue()).startsWith("'=");
            assertThat(data.getCell(6).getStringCellValue()).startsWith("'+");
        }
    }

    @Test
    void fullWorkbookAppendsSummedTotalRowForMultipleTasks() throws Exception {
        Path file = tempDir.resolve("full-total.xlsx");
        MarketingTaskSummaryExportRow first = summary(101L, "任务A", 2, 3, 4, 5);
        MarketingTaskSummaryExportRow second = summary(102L, "任务B", 7, 11, 13, 17);

        var result = writer.writeFull(
                file,
                java.util.List.of(first, second),
                java.util.List.of(),
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:21:00Z"));

        assertThat(result.summaryRowCount()).isEqualTo(3);
        try (InputStream input = Files.newInputStream(file);
             var workbook = WorkbookFactory.create(input)) {
            var total = workbook.getSheet("营销任务汇总").getRow(3);
            assertThat(total.getCell(4).getStringCellValue()).isEqualTo("合计");
            assertThat(total.getCell(5).getNumericCellValue()).isEqualTo(9);
            assertThat(total.getCell(14).getNumericCellValue()).isEqualTo(14);
            assertThat(total.getCell(15).getNumericCellValue()).isEqualTo(17);
            assertThat(total.getCell(16).getNumericCellValue()).isEqualTo(22);
            assertThat(total.getCell(17).getNumericCellValue()).isEqualTo(12);
        }
    }

    private static MarketingTaskSummaryExportRow summary(Long taskId,
                                                          String taskName,
                                                          int totalGroups,
                                                          int planned,
                                                          int success,
                                                          int failed) {
        MarketingTaskSummaryExportRow row = new MarketingTaskSummaryExportRow();
        row.setTaskId(taskId);
        row.setTaskName(taskName);
        row.setTotalGroupCount(totalGroups);
        row.setNormalGroupCount(1);
        row.setBannedGroupCount(1);
        row.setDissolvedGroupCount(0);
        row.setKickedGroupCount(0);
        row.setNoPermissionGroupCount(0);
        row.setTotalAccountCount(1);
        row.setOnlineAccountCount(1);
        row.setAbnormalAccountCount(0);
        row.setPlannedCount(planned);
        row.setSuccessCount(success);
        row.setFailedCount(failed);
        row.setUnknownCount(6);
        return row;
    }
}
