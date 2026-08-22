package com.armada.group.model.vo;

import com.armada.group.model.enums.GroupCreatorLeaveStatus;

/** 群主退群执行结果。 */
public record GroupCreatorLeaveResultVO(
        GroupCreatorLeaveStatus status,
        String message) {
}
