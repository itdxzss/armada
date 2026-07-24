package com.armada.marketing.grouppull.model.vo;

/**
 * 拉群营销任务的一条正式建群执行明细。
 *
 * @param executionId 单建群账号执行 ID
 * @param builderAccountPhone 建群账号号码
 * @param marketingAccountPhone 营销账号号码；尚未完成匹配时可空
 * @param groupName 正式建群前冻结的群名称
 * @param groupJid WhatsApp 群 JID；创建失败时可空
 * @param groupInviteUrl 群邀请链接；获取失败时可空
 * @param groupStatus 当前群状态码；未取得群 JID 时可空
 * @param materialJoinedCount 实际成功进群的料子数量
 * @param groupMemberCount 拉人完成后查询到的群成员总数；查询失败时可空
 * @param sentMessageCount 当前群累计发送成功的营销消息数量
 * @param speakPermission 任务配置的群发言权限码
 * @param builderExitEnabled 任务是否要求建群账号退出群组
 * @param builderExitStatus 建群账号实际退群状态码
 * @param marketerAdminStatus 营销账号管理员设置状态码
 * @param executionStatus 建群执行结果状态码
 * @param failureStage 最终失败发生的执行阶段码；成功记录为完成阶段
 * @param failureReason 建群或非致命步骤的失败原因；无失败时可空
 * @param marketingSendStatus 现有营销目标状态码；未创建营销目标时可空
 * @param lastSentAt 当前群最近一次营销发送成功时间（epoch 毫秒）
 * @param groupCreatedAt WhatsApp 群实际创建成功时间（epoch 毫秒）；未创建时可空
 */
public record GroupPullMarketingGroupVO(
        Long executionId,
        String builderAccountPhone,
        String marketingAccountPhone,
        String groupName,
        String groupJid,
        String groupInviteUrl,
        Integer groupStatus,
        Integer materialJoinedCount,
        Integer groupMemberCount,
        Integer sentMessageCount,
        Integer speakPermission,
        Boolean builderExitEnabled,
        Integer builderExitStatus,
        Integer marketerAdminStatus,
        Integer executionStatus,
        Integer failureStage,
        String failureReason,
        Integer marketingSendStatus,
        Long lastSentAt,
        Long groupCreatedAt) {
}
