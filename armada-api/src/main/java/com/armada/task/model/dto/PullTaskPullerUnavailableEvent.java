package com.armada.task.model.dto;

/** 拉手账号级不可用已持久化，通知未知结果收敛器尽快核实该拉手的在途调用。 */
public record PullTaskPullerUnavailableEvent(
        long tenantId,
        long groupExecutionId,
        long pullerGroupAccountId,
        long occurredAt) {
}
