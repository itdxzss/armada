package com.armada.account.model.entity;

/**
 * 账号导入明细首次登录结果结算参数。
 *
 * <p>作为 Mapper 参数对象使用,把结算 SQL 需要的阶段码、结果码和时间统一收拢,
 * 避免 Mapper 方法出现过多散参。</p>
 */
public class AccountImportLoginResultSettlement {

    /** account.id,用于定位导入明细。 */
    private Long accountId;

    /** 只允许从已派发上线命令阶段结算。 */
    private Integer dispatchedPhase;

    /** 结算成功后写入的终态阶段。 */
    private Integer settledPhase;

    /** 只结算导入成功的明细。 */
    private Integer successParseResult;

    /** 首次登录结果码:成功、失败、封禁或密钥异常。 */
    private Integer loginResult;

    /** 登录结果冻结时间(epoch 毫秒),同时用于过滤派发时间之后的回执。 */
    private Long loginSettledAt;

    /** 登录失败/异常原因;成功时为空。 */
    private String loginReason;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getDispatchedPhase() {
        return dispatchedPhase;
    }

    public void setDispatchedPhase(Integer dispatchedPhase) {
        this.dispatchedPhase = dispatchedPhase;
    }

    public Integer getSettledPhase() {
        return settledPhase;
    }

    public void setSettledPhase(Integer settledPhase) {
        this.settledPhase = settledPhase;
    }

    public Integer getSuccessParseResult() {
        return successParseResult;
    }

    public void setSuccessParseResult(Integer successParseResult) {
        this.successParseResult = successParseResult;
    }

    public Integer getLoginResult() {
        return loginResult;
    }

    public void setLoginResult(Integer loginResult) {
        this.loginResult = loginResult;
    }

    public Long getLoginSettledAt() {
        return loginSettledAt;
    }

    public void setLoginSettledAt(Long loginSettledAt) {
        this.loginSettledAt = loginSettledAt;
    }

    public String getLoginReason() {
        return loginReason;
    }

    public void setLoginReason(String loginReason) {
        this.loginReason = loginReason;
    }
}
