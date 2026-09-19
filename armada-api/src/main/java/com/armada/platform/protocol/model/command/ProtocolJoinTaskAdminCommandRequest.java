package com.armada.platform.protocol.model.command;

/** 进群任务提管理员命令；目标成员在发送时从所属明细补全。 */
public record ProtocolJoinTaskAdminCommandRequest(
        Long tenantId, Long joinTaskId, Long joinTaskResultId, ProtocolAccountRef actor) {
    /** 与双协议约定的命令来源。 */
    public static final String SOURCE = "join_task_admin";
    /** 与其他业务动作隔离的聚合类型。 */
    public static final String AGGREGATE = "JOIN_TASK_ADMIN";
}
