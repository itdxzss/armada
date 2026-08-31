package com.armada.account.model.enums;

/** account_credential.cred_format 的运行时凭据编码。 */
public final class AccountCredentialFormatCode {

    private AccountCredentialFormatCode() {
    }

    /** Android 五/六段，入库后统一为六字段对象。 */
    public static final int SIX_SEGMENT = 1;

    /** Web Baileys JSON。 */
    public static final int BAILEYS_JSON = 2;

    /** 历史全参格式。 */
    public static final int PARAMS = 3;

    /** iOS 主设备原生完整凭据。 */
    public static final int IOS_NATIVE_FULL = 4;
}
