package com.armada.platform.protocol.model.command;

/**
 * 普通拉群任务管理员整块应用「群信息设置」的 Outbox 命令请求。
 *
 * <p>与建群链路彻底分开：建群自己的群设置命令保持原有命令类型和来源不变，字段全部必填；
 * 本命令另起 {@link #COMMAND_TYPE}，所有设置项可选，缺省即「这一项别动」。老群里已经有客户
 * 自己配好的群资料，混用建群那条必填契约会把没勾的项一律按默认值覆写掉。</p>
 *
 * <p>与同域的 {@code pull_task_group_settings} 也不是一回事：那条是一条命令一个设置项的旧
 * 单项命令（放开加人权限、关闭进群审核），仍在用；本命令一次带齐整块群资料。</p>
 *
 * <p>设置项本身不进 Outbox 引用：任务级配置行随时可被运营改动，发命令时现取才是唯一事实，
 * 存进引用等于同一事实留两份可能不一致的副本。</p>
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 群信息设置动作行 ID，同时是 Outbox 聚合关联键
 * @param manager 执行群设置的任务管理员协议账号，其 backend 决定命令进哪个 Topic
 */
public record ProtocolPullTaskGroupProfileCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef manager
) {

    /** 命令类型：协议两端按它分派「整块群资料设置」执行器。 */
    public static final String COMMAND_TYPE = "group.profile.apply";

    /** 命令来源：协议结果按 source 回分派，不与旧单项命令共用。 */
    public static final String SOURCE = "pull_task_group_profile";

    /** Outbox 聚合类型，关联键取群信息设置动作行 ID，与拉群其它命令一致。 */
    public static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";

    /**
     * 生成不含账号、群和设置项的持久化引用。
     *
     * <p>复用拉群成员动作的通用引用形状：本命令的引用字段与它完全相同，
     * 只有 {@code source} 不同。</p>
     */
    public ProtocolPullTaskParticipantActionReference reference() {
        return new ProtocolPullTaskParticipantActionReference(
                tenantId, pullTaskId, groupExecutionId, actionId, SOURCE);
    }
}
