package com.armada.hyperlink.data.model.vo;

/** 跨租户清理扫描返回的显式 tenant/id 对。 */
public class DataPackagePhoneCleanupRow {

    /** 号码所属租户 ID。 */
    private Long tenantId;
    /** 待清理号码行 ID。 */
    private Long id;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
