package com.armada.account.model.entity;

/**
 * 账号导入批次实体,映射 account_import_batch 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 */
public class AccountImportBatch {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入)。 */
    private Long tenantId;

    /** 导入目标分组(→account_group.id)。 */
    private Long accountGroupId;

    /** 上传文件原名;纯文本导入时为「导入」兜底串。 */
    private String sourceFileName;

    /** 原始导入容器类型:ZIP/TXT;仅新增批次保证有值。 */
    private String sourceFileType;

    /** 导入格式:1六段 2JSON 3全参。 */
    private Integer importFormat;

    /** 机型:1安卓 2苹果。 */
    private Integer deviceOs;

    /** 账号类型:1个人 2商业。 */
    private Integer accountType;

    /** 导入时选的 IP 国家。 */
    private String ipRegion;

    /** 解析总行数。 */
    private Integer totalRows;

    /** 成功入库行数。 */
    private Integer importedRows;

    /** 重复行数(批内/库内)。 */
    private Integer duplicateRows;

    /** 格式/凭据不全行数。 */
    private Integer formatErrorRows;

    /** 登录成功(step1 NULL=未登录)。 */
    private Integer loginSuccess;

    /** 登录失败(step1 NULL)。 */
    private Integer loginFailed;

    /** 登录异常密钥/封号(step1 NULL)。 */
    private Integer loginAbnormal;

    /** 批次状态:1进行中 2已完成(step1 同步导入即2)。 */
    private Integer status;

    /** 导入时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 创建人 user_id。 */
    private Long createdBy;

    /** 软删时间(epoch 毫秒);NULL=未删。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getSourceFileType() {
        return sourceFileType;
    }

    public void setSourceFileType(String sourceFileType) {
        this.sourceFileType = sourceFileType;
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

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
