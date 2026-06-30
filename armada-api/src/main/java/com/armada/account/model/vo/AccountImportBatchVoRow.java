package com.armada.account.model.vo;

/**
 * Mapper 投影:account_import_batch LEFT JOIN account_group,用于批次分页列表。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒(UTC)。
 */
public class AccountImportBatchVoRow {

    /** 批次主键。 */
    private Long id;

    /** 来源文件名;文件导入时为原始文件名,纯文本粘贴时为「导入」兜底串。 */
    private String sourceFileName;

    /** 导入格式:1六段 2JSON 3全参。 */
    private Integer importFormat;

    /** 机型:1安卓 2苹果。 */
    private Integer deviceOs;

    /** 账号类型:1个人 2商业。 */
    private Integer accountType;

    /** 导入时选择的 IP 国家/地区。 */
    private String ipRegion;

    /** 解析总行数。 */
    private Integer totalRows;

    /** 成功入库行数。 */
    private Integer importedRows;

    /** 重复行数(批内/库内)。 */
    private Integer duplicateRows;

    /** 格式/凭据不全行数。 */
    private Integer formatErrorRows;

    /** 登录成功数;Service 列表返回前会用 detail.login_result 聚合值覆盖。 */
    private Integer loginSuccess;

    /** 登录失败数;Service 列表返回前会用 detail.login_result 聚合值覆盖。 */
    private Integer loginFailed;

    /** 登录异常数;Service 列表返回前会用 detail.login_result 聚合值覆盖。 */
    private Integer loginAbnormal;

    /** 批次状态:1进行中 2已完成。 */
    private Integer status;

    /** 目标分组名称(LEFT JOIN account_group,分组被软删时为 null)。 */
    private String groupName;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Integer getImportFormat() {
        return importFormat;
    }

    public void setImportFormat(Integer importFormat) {
        this.importFormat = importFormat;
    }

    public Integer getDeviceOs() {
        return deviceOs;
    }

    public void setDeviceOs(Integer deviceOs) {
        this.deviceOs = deviceOs;
    }

    public Integer getAccountType() {
        return accountType;
    }

    public void setAccountType(Integer accountType) {
        this.accountType = accountType;
    }

    public String getIpRegion() {
        return ipRegion;
    }

    public void setIpRegion(String ipRegion) {
        this.ipRegion = ipRegion;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer totalRows) {
        this.totalRows = totalRows;
    }

    public Integer getImportedRows() {
        return importedRows;
    }

    public void setImportedRows(Integer importedRows) {
        this.importedRows = importedRows;
    }

    public Integer getDuplicateRows() {
        return duplicateRows;
    }

    public void setDuplicateRows(Integer duplicateRows) {
        this.duplicateRows = duplicateRows;
    }

    public Integer getFormatErrorRows() {
        return formatErrorRows;
    }

    public void setFormatErrorRows(Integer formatErrorRows) {
        this.formatErrorRows = formatErrorRows;
    }

    public Integer getLoginSuccess() {
        return loginSuccess;
    }

    public void setLoginSuccess(Integer loginSuccess) {
        this.loginSuccess = loginSuccess;
    }

    public Integer getLoginFailed() {
        return loginFailed;
    }

    public void setLoginFailed(Integer loginFailed) {
        this.loginFailed = loginFailed;
    }

    public Integer getLoginAbnormal() {
        return loginAbnormal;
    }

    public void setLoginAbnormal(Integer loginAbnormal) {
        this.loginAbnormal = loginAbnormal;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
