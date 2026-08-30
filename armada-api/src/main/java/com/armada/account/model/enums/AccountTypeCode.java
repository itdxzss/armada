package com.armada.account.model.enums;

/** WhatsApp 账号业务类型码。 */
public final class AccountTypeCode {

    private AccountTypeCode() {
    }

    /** 个人账号。 */
    public static final int PERSONAL = 1;

    /** 商业账号，包括标准商业号和认证商业号。 */
    public static final int BUSINESS = 2;
}
