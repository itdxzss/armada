package com.armada.account.model.entity;

/**
 * 账号导入明细登录结果常量。
 *
 * <p>对应 {@code account_import_detail.login_result} 存储编码。</p>
 */
public final class AccountImportLoginResult {

    /** 本次导入触发的首次登录成功。 */
    public static final int SUCCESS = 1;

    /** 本次导入触发的首次登录失败。 */
    public static final int FAILED = 2;

    /** 凭据/密钥异常,需要重新导入或修复凭据。 */
    public static final int KEY_ABNORMAL = 3;

    /** WhatsApp 封号。 */
    public static final int BANNED = 4;

    private AccountImportLoginResult() {
    }
}
