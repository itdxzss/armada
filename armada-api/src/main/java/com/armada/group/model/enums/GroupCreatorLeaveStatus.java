package com.armada.group.model.enums;

/** 群主退群的一次终态。 */
public enum GroupCreatorLeaveStatus {

    SUCCESS(1),
    NOT_CREATOR(2),
    CREATOR_UNAVAILABLE(3),
    NO_AVAILABLE_CONTROLLER(4),
    PROMOTION_FAILED(5),
    LEAVE_FAILED(6);

    private final int code;

    GroupCreatorLeaveStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
