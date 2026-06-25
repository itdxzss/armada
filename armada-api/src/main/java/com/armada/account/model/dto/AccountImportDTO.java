package com.armada.account.model.dto;

/**
 * 账号导入元信息 DTO。
 *
 * <p>由 Controller 从 multipart/表单参数接收后传给 {@code AccountImportService.importAccounts}。
 * 文件字节和文本内容由 Controller 另行以 {@code byte[]} / {@code String} 入参传入,
 * 不放入本 DTO 避免传输对象过重。</p>
 *
 * @param accountGroupId 目标分组 ID;为 null 时由 Service 自动取系统默认分组
 * @param importFormat   导入格式编码:1六段 2JSON 3全参,对应 {@link com.armada.account.model.entity.ImportFormat}
 * @param deviceOs       机型:1安卓 2苹果(选填,可为 null)
 * @param accountType    账号类型:1个人 2商业;导入即冻结,后续操作不得改写
 * @param ipRegion       导入时选择的 IP 国家/地区名称
 * @param remark         备注(可为 null)
 * @param sourceFileName 来源文件名;文件导入时由 Controller 从 MultipartFile 取得,文本粘贴时为 null;
 *                       纯文本粘贴时为 null,Service 层兜底写入「导入」常量串
 */
public record AccountImportDTO(
        Long accountGroupId,
        Integer importFormat,
        Integer deviceOs,
        Integer accountType,
        String ipRegion,
        String remark,
        String sourceFileName
) {
}
