package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.vo.AccountImportExportRow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountImportExportFilenameTest {

    private static final LocalDate EXPORT_DATE = LocalDate.of(2026, 7, 3);

    @Test
    void successScope_includesDateStatusAndSuccessCount() {
        String filename = AccountImportExportFilename.build(
                EXPORT_DATE,
                "success",
                "zip",
                List.of(row(1, null), row(1, null)));

        assertThat(filename).isEqualTo("账号导入_20260703_成功_2个.zip");
    }

    @Test
    void failScope_includesDateStatusAndFailCount() {
        String filename = AccountImportExportFilename.build(
                EXPORT_DATE,
                "fail",
                "txt",
                List.of(row(2, null), row(3, null), row(1, 4)));

        assertThat(filename).isEqualTo("账号导入_20260703_失败_3个.txt");
    }

    @Test
    void allScope_includesTotalSuccessAndFailCounts() {
        String filename = AccountImportExportFilename.build(
                EXPORT_DATE,
                "all",
                "zip",
                List.of(row(1, 1), row(1, 2), row(3, null), row(1, null)));

        assertThat(filename).isEqualTo("账号导入_20260703_全部_共4个_成功1个_失败2个.zip");
    }

    private static AccountImportExportRow row(Integer parseResult, Integer loginResult) {
        AccountImportExportRow row = new AccountImportExportRow();
        row.setParseResult(parseResult);
        row.setLoginResult(loginResult);
        row.setRawPayload("{}");
        return row;
    }
}
