package com.armada.account.model.enums;

/** WhatsApp 商业认证级别；与个人/商业账号类型正交。 */
public final class BusinessVerificationLevelCode {

    private BusinessVerificationLevelCode() {
    }

    /** 服务端明确返回 verified_level=high，可展示蓝标。 */
    public static final int HIGH = 1;

    /** 服务端明确返回 verified_level，但不是 high。 */
    public static final int NOT_HIGH = 2;
}
