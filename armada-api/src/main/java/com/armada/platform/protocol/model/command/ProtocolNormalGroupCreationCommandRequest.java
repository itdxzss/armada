package com.armada.platform.protocol.model.command;

/**
 * 新建普群协议动作的 Outbox 引用。
 *
 * <p>路由账号决定命令进入 Web 或 Android 命令 Topic；payload 只持久化冻结业务引用，
 * 真正的手机号、群名、群成员和权限参数由业务域 hydrator 在发布前补全。</p>
 *
 * @param tenantId 租户 ID
 * @param taskId 新建普群任务 ID
 * @param itemId 计划群明细 ID
 * @param memberId 联系人准备对应成员 ID；其它动作为空
 * @param direction 联系人方向：CREATOR_SAVE_MEMBER/MEMBER_SAVE_CREATOR；其它动作为空
 * @param action 通用动作：CONTACT_PREPARE/GROUP_CREATE/GROUP_SETTINGS_APPLY/GROUP_LEAVE
 * @param actor 实际执行动作的协议账号；其 backend 是唯一 Topic 路由依据
 */
public record ProtocolNormalGroupCreationCommandRequest(
        Long tenantId,
        Long taskId,
        Long itemId,
        Long memberId,
        String direction,
        String action,
        ProtocolAccountRef actor
) {

    /** 统一业务来源，协议结果按 source + action 严格分派。 */
    public static final String SOURCE = "normal_group_creation";

    /** 生成不含协议执行敏感参数的持久化引用。 */
    public ProtocolNormalGroupCreationReference reference() {
        return new ProtocolNormalGroupCreationReference(
                tenantId, taskId, itemId, memberId, direction, action, SOURCE);
    }
}
