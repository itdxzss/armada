package com.armada.account.model.entity;

/**
 * 账号状态码常量。
 *
 * <p>对应 account_state 表 account_state 列口径;
 * 所有业务代码必须引用本类常量,禁止直接使用魔法数字。</p>
 */
public final class AccountStateCode {

    private AccountStateCode() {
    }

    /**
     * 新号/待上线:账号已导入但尚未完成登录,协议层未上报状态。
     */
    public static final int NEW = 1;

    /**
     * 正常:账号在线、登录态健康,可正常派单。
     */
    public static final int NORMAL = 2;

    /**
     * 封禁:账号被 WhatsApp 封禁(401/403/440 NEED_REAUTH),不可正常使用。
     */
    public static final int BANNED = 3;

    /**
     * 导出:账号已被导出解绑,仅限在此状态下可执行删除。
     */
    public static final int EXPORTED = 4;

    /**
     * 解绑:账号已从当前租户解绑,仅限在此状态下可执行删除。
     */
    public static final int UNBOUND = 5;
}
