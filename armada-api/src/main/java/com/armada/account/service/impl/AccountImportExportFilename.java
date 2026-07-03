package com.armada.account.service.impl;

import com.armada.account.model.vo.AccountImportExportRow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 账号导入导出文件名生成器。
 *
 * <p>导出内容仍由原服务负责；这里仅收口命名口径，避免 ZIP/TXT 分支各自拼字符串。</p>
 */
final class AccountImportExportFilename {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private AccountImportExportFilename() {
    }

    static String build(LocalDate exportDate,
                        String scope,
                        String extension,
                        List<AccountImportExportRow> rows) {
        String date = exportDate.format(DATE_FORMAT);
        List<AccountImportExportRow> safeRows = rows == null ? List.of() : rows;
        boolean hasLoginResult = safeRows.stream().anyMatch(row -> row.getLoginResult() != null);
        return switch (scope) {
            case "success" -> "账号导入_" + date + "_成功_" + safeRows.size() + "个." + extension;
            case "fail" -> "账号导入_" + date + "_失败_" + safeRows.size() + "个." + extension;
            default -> "账号导入_" + date + "_全部_共" + safeRows.size()
                    + "个_成功" + successCount(safeRows, hasLoginResult)
                    + "个_失败" + failCount(safeRows) + "个." + extension;
        };
    }

    private static long successCount(List<AccountImportExportRow> rows, boolean hasLoginResult) {
        return rows.stream().filter(row -> isSuccess(row, hasLoginResult)).count();
    }

    private static long failCount(List<AccountImportExportRow> rows) {
        return rows.stream().filter(AccountImportExportFilename::isFail).count();
    }

    private static boolean isSuccess(AccountImportExportRow row, boolean hasLoginResult) {
        Integer loginResult = row.getLoginResult();
        if (loginResult != null) {
            return loginResult == 1;
        }
        if (hasLoginResult) {
            return false;
        }
        return Integer.valueOf(1).equals(row.getParseResult());
    }

    private static boolean isFail(AccountImportExportRow row) {
        Integer loginResult = row.getLoginResult();
        if (loginResult != null) {
            return loginResult == 2 || loginResult == 3 || loginResult == 4;
        }
        Integer parseResult = row.getParseResult();
        return parseResult != null && (parseResult == 2 || parseResult == 3 || parseResult == 4);
    }
}
