package com.armada.account.model.enums;

/**
 * 账号批量操作范围。
 */
public enum AccountBatchScope {

    /** 只处理请求中明确提供的账号 ID。 */
    IDS,

    /** 处理符合后端查询条件的全部账号。 */
    QUERY
}
