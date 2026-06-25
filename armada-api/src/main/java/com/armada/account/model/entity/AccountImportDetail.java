package com.armada.account.model.entity;

/**
 * 账号导入明细实体,映射 account_import_detail 表一行。裸 POJO + getter/setter(无 Lombok),Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒(非 LocalDateTime),insert 时须由调用方显式传入。
 */
public class AccountImportDetail {

    /** 主键。 */
    private Long id;

    /** 租户 ID(拦截器注入)。 */
    private Long tenantId;

    /** 所属批次(→account_import_batch.id)。 */
    private Long batchId;

    /** 行号。 */
    private Integer lineNo;

    /** 该行 WA 号。 */
    private String wsPhone;

    /** 成功入库时回填(→account.id)。 */
    private Long accountId;

    /** 解析结果:1成功入库 2重复(批内或库内已存在) 3格式错误 4凭据不全。 */
    private Integer parseResult;

    /** 失败原因。 */
    private String failReason;

    /** NULL=未登录/跳过(step1);step3:1成功 2失败 3密钥异常 4封号。 */
    private Integer loginResult;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

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

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getParseResult() {
        return parseResult;
    }

    public void setParseResult(Integer parseResult) {
        this.parseResult = parseResult;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Integer getLoginResult() {
        return loginResult;
    }

    public void setLoginResult(Integer loginResult) {
        this.loginResult = loginResult;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
