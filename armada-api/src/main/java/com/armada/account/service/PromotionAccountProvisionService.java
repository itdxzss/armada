package com.armada.account.service;

/** 配对流程进入账号域的最小边界，不向配对编排暴露账号 Mapper。 */
public interface PromotionAccountProvisionService {

    /** 判断当前租户是否已经存在该手机号的活跃账号。 */
    boolean existsActiveByPhone(String phone);

    /** 判断任意租户是否已经存在该手机号的活跃账号。仅供公开推广配对入口使用。 */
    boolean existsActiveByPhoneGlobally(String phone);

    /** 校验控台导号目标分组属于当前租户且仍有效。 */
    void validateControlTarget(Long accountGroupId);

    /** 原子写入 account、account_state、account_credential 并返回正式账号 ID。 */
    Long provision(PromotionAccountProvisionCommand command);

    /** 原子写入控台导入的普通自购 Web 账号及在线凭据。 */
    Long provisionControl(AccountPairingProvisionCommand command);
}
