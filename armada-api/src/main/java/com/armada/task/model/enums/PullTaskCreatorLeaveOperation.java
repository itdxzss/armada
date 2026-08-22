package com.armada.task.model.enums;

/** 群主退群异步链路中的协议动作。 */
public enum PullTaskCreatorLeaveOperation {
    /** 把普通控端成员提升为管理员。 */
    PROMOTE,
    /** 建群者退出群组。 */
    LEAVE
}
