package com.armada.task.model.dto;

/** 当前进群尝试失败后的条件重排参数；命令和尝试序号防止迟到结果覆盖新命令。 */
public record JoinTaskRetryTransition(
        Long id,
        String reason,
        long nextExecuteAt,
        long now,
        String commandId,
        int attemptNo) {
}
