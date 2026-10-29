package com.armada.account.wbcompanion.model.entity;

/** 安卓号登录到 WB 分身的一次任务（表 wb_companion_task）；按条串行推进，失败即停。 */
public class WbCompanionTask {
    /** 任务主键。 */
    private Long id;
    /** 账号所属租户 ID。 */
    private Long tenantId;
    /** 安卓协议账号 ID。 */
    private Long accountId;
    /** 账号号码快照，只含数字。 */
    private String wsPhone;
    /** WB 环境标识。 */
    private String wbEnvId;
    /** WB 会员 ID。 */
    private Long wbMemberId;
    /** WB 会员用户名快照。 */
    private String wbUsername;
    /** 开始时 WB 上已有的连接数。 */
    private Integer baseCount;
    /** 目标连接数 1-4，含已有连接。 */
    private Integer targetCount;
    /** 本任务已成功挂上的连接数。 */
    private Integer succeededCount;
    /** 任务状态，取值见 WbCompanionTaskStatus。 */
    private Integer status;
    /** 当前这一条的步骤，取值见 WbCompanionStep。 */
    private Integer step;
    /** 当前这一条的 WB 配对 ID。 */
    private String wbPairingId;
    /** 当前这一条在 WB 的角色：MAIN、REPAIR、SHADOW。 */
    private String wbRole;
    /** 当前这一条的配对码，这一条结束即清空，不得输出。 */
    private String pairCode;
    /** 当前步骤截止时间（epoch 毫秒）。 */
    private Long stepDeadline;
    /** 下次推进时间（epoch 毫秒），推进中作为租约。 */
    private Long nextRunAt;
    /** 失败码。 */
    private String errorCode;
    /** 发起人用户 ID。 */
    private Long createdBy;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
    private Long updatedAt;
    /** 结束时间（epoch 毫秒）。 */
    private Long finishedAt;

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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public String getWbEnvId() {
        return wbEnvId;
    }

    public void setWbEnvId(String wbEnvId) {
        this.wbEnvId = wbEnvId;
    }

    public Long getWbMemberId() {
        return wbMemberId;
    }

    public void setWbMemberId(Long wbMemberId) {
        this.wbMemberId = wbMemberId;
    }

    public String getWbUsername() {
        return wbUsername;
    }

    public void setWbUsername(String wbUsername) {
        this.wbUsername = wbUsername;
    }

    public Integer getBaseCount() {
        return baseCount;
    }

    public void setBaseCount(Integer baseCount) {
        this.baseCount = baseCount;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Integer targetCount) {
        this.targetCount = targetCount;
    }

    public Integer getSucceededCount() {
        return succeededCount;
    }

    public void setSucceededCount(Integer succeededCount) {
        this.succeededCount = succeededCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public String getWbPairingId() {
        return wbPairingId;
    }

    public void setWbPairingId(String wbPairingId) {
        this.wbPairingId = wbPairingId;
    }

    public String getWbRole() {
        return wbRole;
    }

    public void setWbRole(String wbRole) {
        this.wbRole = wbRole;
    }

    public String getPairCode() {
        return pairCode;
    }

    public void setPairCode(String pairCode) {
        this.pairCode = pairCode;
    }

    public Long getStepDeadline() {
        return stepDeadline;
    }

    public void setStepDeadline(Long stepDeadline) {
        this.stepDeadline = stepDeadline;
    }

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

}
