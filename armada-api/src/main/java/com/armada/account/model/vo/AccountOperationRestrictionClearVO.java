package com.armada.account.model.vo;

/**
 * 账号业务风控人工解除结果。
 *
 * @param requested 去重后请求账号数
 * @param cleared 当前租户实际建立解除水位的账号数
 */
public record AccountOperationRestrictionClearVO(int requested, int cleared) {
}
