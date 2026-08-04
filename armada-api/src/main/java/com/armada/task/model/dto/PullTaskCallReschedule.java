package com.armada.task.model.dto;

/**
 * 拉手不可调度时，把未取得业务结果的已提交调用退回原冻结计划。
 *
 * @param scope 调用、原命令与发生时间
 * @param status 允许的前置状态与目标状态
 * @param reasonCode 稳定原因码
 * @param reasonMessage 已脱敏原因描述
 */
public record PullTaskCallReschedule(
        Scope scope,
        Status status,
        String reasonCode,
        String reasonMessage) {

    /** @param callId 调用 ID @param expectedCommandId 原命令 ID @param now 发生时间 */
    public record Scope(long callId, String expectedCommandId, long now) {
    }

    /** @param expected 允许退回的前置状态 @param target 退回后的计划状态 */
    public record Status(int expected, int target) {
    }
}
