package com.armada.account.model.vo;

/**
 * Mapper 投影:按导入批次聚合 account_import_detail.login_result。
 */
public class AccountImportLoginStatsRow {

    /** 批次主键。 */
    private Long batchId;

    /** 登录成功数。 */
    private Integer loginSuccess;

    /** 登录失败数。 */
    private Integer loginFailed;

    /** 登录异常数:密钥异常 + 封号。 */
    private Integer loginAbnormal;

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
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
}
