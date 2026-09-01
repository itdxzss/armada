package com.armada.account.model.enums;

/** 普通拉群拉手专用限制状态。 */
public enum AccountPullerRestrictionStatus {

    /** 允许作为普通拉群拉手，仍须通过账号在线和生命周期校验。 */
    ALLOWED(1),

    /** 拉人动作受限，等待 Armada 到期恢复。 */
    RESTRICTED(2);

    private final int code;

    AccountPullerRestrictionStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储码 */
    public int code() {
        return code;
    }
}
