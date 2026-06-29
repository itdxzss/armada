package com.armada.account.model.vo;

/**
 * 账号导入时选择的 IP 国家投影。
 *
 * <p>用于账号上线前确定代理分配偏好。字段来自
 * account_import_detail.account_id → account_import_batch.ip_region。</p>
 */
public class AccountIpRegionRow {

    /** 账号主键。 */
    private Long accountId;

    /** 导入批次选择的 IP 国家/地区。 */
    private String ipRegion;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getIpRegion() {
        return ipRegion;
    }

    public void setIpRegion(String ipRegion) {
        this.ipRegion = ipRegion;
    }
}
