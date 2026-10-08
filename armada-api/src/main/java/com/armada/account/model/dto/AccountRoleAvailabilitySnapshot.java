package com.armada.account.model.dto;

/**
 * 账号域批量查询角色可用性所需的持久化事实；不作为跨域接口返回。
 *
 * @param accountId 当前租户账号 ID
 * @param accountState 账号生命周期
 * @param loginState 实际登录状态
 * @param desiredLoginState 人工期望登录状态
 * @param muteStatus 禁言状态
 * @param offlineSince 本次连续离线开始时间
 * @param trippedAt 熔断时间，非空表示仍需人工清零
 * @param reservationTenantId 预留记录原始租户，可来自同一身份的其他租户账号
 * @param reservationLifecycle 预留或注销生命周期
 * @param reservationTaskId 预留所属任务
 * @param reservationGroupExecutionId 预留所属执行行
 */
public record AccountRoleAvailabilitySnapshot(Long accountId, Integer accountState, Integer loginState,
        Integer desiredLoginState, Integer muteStatus, Long offlineSince, Long trippedAt,
        Long reservationTenantId, String reservationLifecycle, Long reservationTaskId,
        Long reservationGroupExecutionId) {
}
