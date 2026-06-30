package com.armada.account.model.vo;

/**
 * 账号导入批次列表条目出参 VO(批次分页列表专用)。
 *
 * <p>由 {@code AccountImportService.listBatches} 返回,供 Controller 直接序列化为 JSON 响应。
 * 与 {@link AccountImportBatchVO}(POST 导入结果)的区别在于多一个 {@code groupName} 字段
 * (来自 LEFT JOIN account_group)。字段全部 camelCase。</p>
 *
 * @param id               批次主键
 * @param sourceFileName   来源文件名;文件导入时为原始文件名,纯文本粘贴时为「导入」兜底串
 * @param importFormat     导入格式编码:1六段 2JSON 3全参
 * @param deviceOs         机型:1安卓 2苹果
 * @param accountType      账号类型:1个人 2商业
 * @param ipRegion         导入时选择的 IP 国家/地区
 * @param totalRows        解析总行数
 * @param importedRows     成功入库行数
 * @param duplicateRows    重复行数(批内+库内)
 * @param formatErrorRows  格式/凭据不全行数
 * @param loginSuccess     登录成功数,列表查询时由明细 login_result 聚合;未出结果为 0
 * @param loginFailed      登录失败数,列表查询时由明细 login_result 聚合;未出结果为 0
 * @param loginAbnormal    登录异常数,列表查询时由明细 login_result 聚合;未出结果为 0
 * @param status           批次状态:1进行中 2已完成
 * @param groupName        目标分组名称(LEFT JOIN account_group,分组被软删时为 null)
 * @param createdAt        创建时间(epoch 毫秒)
 */
public record AccountImportBatchListVO(
        Long id,
        String sourceFileName,
        Integer importFormat,
        Integer deviceOs,
        Integer accountType,
        String ipRegion,
        Integer totalRows,
        Integer importedRows,
        Integer duplicateRows,
        Integer formatErrorRows,
        Integer loginSuccess,
        Integer loginFailed,
        Integer loginAbnormal,
        Integer status,
        String groupName,
        Long createdAt
) {
}
