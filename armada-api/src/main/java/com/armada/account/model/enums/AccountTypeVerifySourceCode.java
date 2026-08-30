package com.armada.account.model.enums;

/** 账号类型协议校验来源码。 */
public final class AccountTypeVerifySourceCode {

    private AccountTypeVerifySourceCode() {
    }

    /** 凭据中的平台或商业名称元数据。 */
    public static final int CREDS_META = 1;

    /** 配对成功回包中的商业身份事实。 */
    public static final int PAIR_SUCCESS = 2;

    /** ONLINE 后商业资料轻量查询。 */
    public static final int BUSINESS_PROFILE_QUERY = 3;
}
