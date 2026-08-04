package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskProtocolOutcome;
import java.util.Objects;

/** 单动作或单号码提权的协议结果回调。 */
public record PullTaskCommandCallback(
        String commandId,
        PullTaskProtocolOutcome outcome,
        String reasonCode,
        String reasonMessage,
        long occurredAt) {

    /** 校验回调定位键和明确结果。 */
    public PullTaskCommandCallback {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空");
        }
        commandId = commandId.trim();
        outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
    }
}
