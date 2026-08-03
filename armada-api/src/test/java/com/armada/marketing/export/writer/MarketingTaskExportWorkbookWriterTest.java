package com.armada.marketing.export.writer;

import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
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
            assertThat(workbook.getSheetName(0)).isEqualTo("国家进群数据");
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 12);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("进群时间");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("实际进群号码");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("营销条数");
        }
    }

    @Test
    void fullWorkbookContainsGroupStatisticsAndMemberDetailSheets() throws Exception {
        Path file = tempDir.resolve("full.xlsx");

        MarketingTaskGroupExportRow group = new MarketingTaskGroupExportRow();
        group.setTaskId(101L);
        group.setGroupLink("https://chat.whatsapp.com/group-101");
        MarketingTaskGroupMemberExportRow member = new MarketingTaskGroupMemberExportRow();
        member.setTaskId(101L);
        member.setMemberPhone("628123456789");
        member.setExitType("主动退群");
        member.setExitedAt(1_785_310_600_000L);

        writer.writeFull(
                file,
                (groupConsumer, memberConsumer) -> {
                    groupConsumer.accept(group);
                    memberConsumer.accept(member);
                },
                Instant.parse("2026-07-29T03:20:00Z"),
                Instant.parse("2026-07-29T03:21:00Z"));

        try (InputStream input = Files.newInputStream(file);
             var workbook = WorkbookFactory.create(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("营销群组统计");
            assertThat(workbook.getSheetName(1)).isEqualTo("群组成员明细");
            assertThat(workbook.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 21);
            assertThat(workbook.getSheetAt(1).getRow(0).getLastCellNum()).isEqualTo((short) 14);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(19).getStringCellValue())
                    .isEqualTo("数据统计截止时间");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(20).getStringCellValue())
                    .isEqualTo("备注");
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(10).getStringCellValue())
                    .isEqualTo("退出方式");
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(12).getStringCellValue())
                    .isEqualTo("退群时间");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(10).getStringCellValue())
                    .isEqualTo("主动退群");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(12).getStringCellValue())
                    .isNotBlank();
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

}
