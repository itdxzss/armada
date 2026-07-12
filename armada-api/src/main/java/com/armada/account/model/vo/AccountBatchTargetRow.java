package com.armada.account.model.vo;

/**
 * 批量账号执行目标 SQL 行。
 *
 * <p>只携带编排所需的最小字段，凭据正文不会进入批量扫描结果或日志。</p>
 */
public class AccountBatchTargetRow {

    private Long id;
    private Integer accountState;
    private boolean credentialPresent;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public boolean isCredentialPresent() {
        return credentialPresent;
    }

    public void setCredentialPresent(boolean credentialPresent) {
        this.credentialPresent = credentialPresent;
    }
}
