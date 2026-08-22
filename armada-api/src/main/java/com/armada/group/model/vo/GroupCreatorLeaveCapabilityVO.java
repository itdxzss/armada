package com.armada.group.model.vo;

/** 群详情抽屉的群主退群按钮能力。 */
public record GroupCreatorLeaveCapabilityVO(
        boolean executable,
        String blockedReasonCode,
        String blockedReason) {
}
