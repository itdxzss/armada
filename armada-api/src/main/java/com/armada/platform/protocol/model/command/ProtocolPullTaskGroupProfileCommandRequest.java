package com.armada.platform.protocol.model.command;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 普通拉群任务应用「群信息设置」的 Outbox 命令请求。
 *
 * <p>群链接模式由管理员执行，新群模式由建群人执行。设置项可选，缺省即「这一项别动」；
 * 新群资料读回确认缺项后，可以只补写名称或简介，避免重复头像与权限设置。</p>
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
 * @param repair 读回确认的补写范围；为空时应用任务配置中的全部设置项
 */
public record ProtocolPullTaskGroupProfileCommandRequest(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        ProtocolAccountRef manager,
        Repair repair
) {

    /** 命令类型：协议两端按它分派「整块群资料设置」执行器。 */
    public static final String COMMAND_TYPE = "group.profile.apply";

    /** 命令来源：协议结果按 source 回分派，不与旧单项命令共用。 */
    public static final String SOURCE = "pull_task_group_profile";

    /** Outbox 聚合类型，关联键取群信息设置动作行 ID，与拉群其它命令一致。 */
    public static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";

    /**
     * 生成不含账号、群和设置值的持久化引用，仅保留补写字段范围。
     *
     * <p>设置值仍由发送时读取任务配置补全；旧引用没有 repair 时保持完整设置语义。</p>
     */
    public Reference reference() {
        return new Reference(tenantId, pullTaskId, groupExecutionId, actionId, SOURCE, repair);
    }

    /**
     * 真实群资料读回后确认需要补写的字段。
     *
     * @param subject 是否补写群名称
     * @param description 是否补写群简介
     */
    public record Repair(boolean subject, boolean description) {
    }

    /**
     * 群资料命令的持久化引用，修复范围不携带设置值。
     *
     * @param tenantId 租户 ID
     * @param pullTaskId 拉群任务 ID
     * @param groupExecutionId 群执行行 ID
     * @param actionId 群资料动作 ID
     * @param source 业务来源
     * @param repair 可选补写范围；为空时应用完整任务配置
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(
            Long tenantId, Long pullTaskId, Long groupExecutionId, Long actionId,
            String source, Repair repair) {
    }
}
