package com.armada.group.model.enums;

/** WhatsApp 群成员状态来源；同一时刻事件优先于完整快照。 */
public enum WhatsappGroupMemberStateSource {
    SNAPSHOT_ABSENT,
    FULL_SNAPSHOT,
    MEMBER_QUERY,
    ADD_EVENT,
    ROLE_EVENT,
    REMOVE_EVENT,
    LEAVE_EVENT,
    UNKNOWN_EXIT_EVENT
}
