package com.armada.account.model;

/**
 * 账号身份命中的建群人预留或注销归属；不包含号码、凭据等敏感数据。
 *
 * @param tenantId 预留记录原始租户，可能与当前账号别名的租户不同，恢复前必须复核
 * @param lifecycle 预留或注销生命周期
 * @param taskId 唯一所属任务
 * @param groupExecutionId 唯一所属执行行
 */
public record AccountCreatorReservation(Long tenantId, String lifecycle, Long taskId, Long groupExecutionId) {
}
