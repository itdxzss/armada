package com.armada.platform.protocol.port.account.command;

/**
 * 上线凭据格式(防腐层语义)。
 *
 * <p>对应 account_credential.cred_format 的三种导入形态。协议层 inline 上线(方案 B)只认这三种,
 * legacy 形态被协议层显式拒绝,故本枚举不含 legacy。int 编码↔枚举、枚举↔协议层 wire 字符串的映射
 * 不放在本枚举内:前者落账号编排(⑤口),后者落 HTTP adapter(③口)。</p>
 */
public enum CredentialFormat {

    /**
     * 六段参数(account_credential.cred_format=1);含 deviceIdentityKey 等六段字段。
     * 注意:六段常因缺 registrationId 等被 WhatsApp 拒登,实际上线建议优先用完整 creds。
     */
    SIX_SEGMENT,

    /**
     * 标准 Baileys JSON(cred_format=2);完整 creds+keys,最可靠的上线凭据。
     */
    BAILEYS_JSON,

    /**
     * 全参数(cred_format=3);移植自全参登录形态。
     */
    PARAMS
}
