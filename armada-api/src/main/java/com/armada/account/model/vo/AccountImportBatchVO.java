package com.armada.account.model.vo;

/**
 * 账号导入批次出参 VO。
 *
 * <p>由 {@code AccountImportService.importAccounts} 返回,供 Controller 直接序列化为 JSON 响应。
 * 字段全部 camelCase(全局无 Jackson 命名策略,默认即 camelCase)。</p>
 *
 * @param id               批次主键
 * @param sourceFileName   来源文件名;文件导入时为原始文件名,纯文本粘贴时为「导入」兜底串
 * @param importFormat     导入格式编码:1六段 2JSON 3全参
 * @param deviceOs         机型:1安卓 2苹果
 * @param accountType      账号类型:1个人 2商业
 * @param ipRegion         导入时选择的 IP 国家/地区
 * @param totalRows        解析总行数
 * @param importedRows     成功入库行数(ImportResult.SUCCESS)
 * @param duplicateRows    重复行数(批内+库内)
 * @param formatErrorRows  格式/凭据不全行数(FORMAT_ERROR + CRED_INCOMPLETE)
 * @param loginSuccess     登录成功数(step1 为 null=未登录)
 * @param loginFailed      登录失败数(step1 为 null)
 * @param loginAbnormal    登录异常数(step1 为 null)
 * @param status           批次状态:1进行中 2已完成(step1 同步导入即为 2)
 * @param createdAt        创建时间(epoch 毫秒)
 */
public record AccountImportBatchVO(
        Long id,
        String sourceFileName,
        Integer importFormat,
        Integer deviceOs,
        Integer accountType,
        String ipRegion,
        int totalRows,
        int importedRows,
        int duplicateRows,
        int formatErrorRows,
        Integer loginSuccess,
        Integer loginFailed,
        Integer loginAbnormal,
        int status,
        Long createdAt
) {
}
