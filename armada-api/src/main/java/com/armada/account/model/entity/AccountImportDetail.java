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

    /** 导入上线阶段:0跳过/不参与 1待派发 2已派发待回写 3已冻结终态。 */
    private Integer onlinePhase;

    /** 上线命令派发时间(epoch 毫秒)。 */
    private Long onlineDispatchedAt;

    /** 本次导入登录结果冻结时间(epoch 毫秒)。 */
    private Long loginSettledAt;

    /** 上线派发重试次数。 */
    private Integer dispatchAttempts;

    /** 登录失败/异常原因或派发错误。 */
    private String loginReason;

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

    public Integer getOnlinePhase() {
        return onlinePhase;
    }

    public void setOnlinePhase(Integer onlinePhase) {
        this.onlinePhase = onlinePhase;
    }

    public Long getOnlineDispatchedAt() {
        return onlineDispatchedAt;
    }

    public void setOnlineDispatchedAt(Long onlineDispatchedAt) {
        this.onlineDispatchedAt = onlineDispatchedAt;
    }

    public Long getLoginSettledAt() {
        return loginSettledAt;
    }

    public void setLoginSettledAt(Long loginSettledAt) {
        this.loginSettledAt = loginSettledAt;
    }

    public Integer getDispatchAttempts() {
        return dispatchAttempts;
    }

    public void setDispatchAttempts(Integer dispatchAttempts) {
        this.dispatchAttempts = dispatchAttempts;
    }

    public String getLoginReason() {
        return loginReason;
    }

    public void setLoginReason(String loginReason) {
        this.loginReason = loginReason;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
