package com.armada.account.model.enums;

/** 账号类型协议校验状态码。 */
public final class AccountTypeVerifyStatusCode {

    private AccountTypeVerifyStatusCode() {
    }

    /** 当前凭据等待首次 ONLINE 后校验。 */
    public static final int PENDING = 0;

    /** 协议识别结果与导入申报一致。 */
    public static final int MATCHED = 1;

    /** 协议识别结果与导入申报不一致，当前有效类型已纠正。 */
    public static final int CORRECTED = 2;

    /** 检测超时、失败或结果不明确，有效类型保持不变。 */
    public static final int INCONCLUSIVE = 3;

    /** 迁移前已存在的账号，尚未进入灰度校验。 */
    public static final int LEGACY_UNVERIFIED = 4;
}
