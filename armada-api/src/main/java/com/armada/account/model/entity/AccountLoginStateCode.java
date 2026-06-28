package com.armada.account.model.entity;

/**
 * 账号登录状态码常量。
 *
 * <p>对应 account_state 表 login_state 列口径;所有业务代码必须引用本类常量,
 * 禁止直接使用魔法数字。</p>
 */
public final class AccountLoginStateCode {

    private AccountLoginStateCode() {
    }

    /**
     * 在线:协议层确认账号已经连接 WhatsApp。
     */
    public static final int ONLINE = 1;

    /**
     * 离线:协议层上报非 ONLINE 状态,包括 OFFLINE、RECONNECTING、NEED_REAUTH 等。
     */
    public static final int OFFLINE = 2;
}
