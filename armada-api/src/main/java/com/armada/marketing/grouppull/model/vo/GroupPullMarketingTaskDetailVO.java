package com.armada.marketing.grouppull.model.vo;

/**
 * 拉群营销任务配置与汇总详情。
 *
 * <p>该视图用于创建成功、生命周期操作和任务详情回显，只返回任务配置及聚合统计，
 * 不展开料子记录或单群执行明细。</p>
 *
 * @param id 统一营销任务 ID
 * @param taskName 任务名称
 * @param status 任务主状态码
 * @param blockReason 当前执行阻塞原因码
 * @param resourceStatus 营销分组及账号资源状态码
 * @param builderGroupId 建群账号来源分组 ID
 * @param successGroupId 建群成功后建群账号转入分组 ID，可空
 * @param failureGroupId 建群失败后建群账号转入分组 ID，可空
 * @param marketingGroupId 营销账号来源分组 ID
 * @param marketingAccountGroupLimit 单个营销账号在本任务中的最大成功进群数
 * @param marketingTemplateId 营销模板 ID
 * @param sendIntervalSeconds 相邻营销轮次的发送间隔（秒）
 * @param groupNamePrefix 群名前缀，可空
 * @param friendRetryLimit 建群账号与营销账号互加好友的最大尝试次数
 * @param materialPerGroup 单群抽取料子数量
 * @param speakPermission 群发言权限码：0=不操作，1=禁言，2=不禁言
 * @param builderExitEnabled 建群完成后建群账号是否退出群组
 * @param remark 任务备注，可空
 * @param taskEndAt 任务计划结束时间（epoch 毫秒）
 * @param totalDataCount 上传文件解析出的有效料子总数
 * @param completedDataCount 已进入建群成功群组的料子数量
 * @param successGroupCount 完整达到任务配置要求的成功群组数
 * @param failedGroupCount 正式进入建群流程后的失败执行数
 * @param marketingAccountTotalCount 启动时锁定营销分组的账号总数，可空
 * @param usedMarketingAccountCount 当前任务已实际分配或使用的营销账号数
 * @param createdAt 任务创建时间（epoch 毫秒）
 * @param updatedAt 任务最近更新时间（epoch 毫秒）
 */
public record GroupPullMarketingTaskDetailVO(
        Long id,
        String taskName,
        Integer status,
        Integer blockReason,
        Integer resourceStatus,
        Long builderGroupId,
        Long successGroupId,
        Long failureGroupId,
        Long marketingGroupId,
        Integer marketingAccountGroupLimit,
        Long marketingTemplateId,
        Integer sendIntervalSeconds,
        String groupNamePrefix,
        Integer friendRetryLimit,
        Integer materialPerGroup,
        Integer speakPermission,
        Boolean builderExitEnabled,
        String remark,
        Long taskEndAt,
        Integer totalDataCount,
        Integer completedDataCount,
        Integer successGroupCount,
        Integer failedGroupCount,
        Integer marketingAccountTotalCount,
        Integer usedMarketingAccountCount,
        Long createdAt,
        Long updatedAt) {
}
