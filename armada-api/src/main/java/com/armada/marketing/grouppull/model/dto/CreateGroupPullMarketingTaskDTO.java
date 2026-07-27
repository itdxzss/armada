package com.armada.marketing.grouppull.model.dto;

/**
 * 创建拉群营销任务的配置入参。
 *
 * <p>料子文件通过同一个 {@code multipart/form-data} 请求的 {@code materialFile} 部分单独提交，
 * 本对象只承载任务配置。</p>
 *
 * @param taskName 任务名称，必填
 * @param builderGroupId 建群账号来源分组 ID，必填
 * @param successGroupId 建群成功后建群账号转入分组 ID，可空
 * @param failureGroupId 建群失败后建群账号转入分组 ID，可空
 * @param marketingGroupId 营销账号来源分组 ID，必填
 * @param marketingAccountGroupLimit 单个营销账号在本任务中的最大成功进群数
 * @param marketingTemplateId 复用现有营销模板菜单保存的模板 ID，必填
 * @param sendIntervalSeconds 相邻营销轮次的发送间隔（秒）
 * @param groupNamePrefix 群名前缀，可空；为空时使用任务名称
 * @param friendRetryLimit 建群账号与营销账号互加失败后的重试次数，不包含首次
 * @param materialPerGroup 单群抽取料子数量
 * @param materialEntryIntervalSeconds 逐个添加料子的基准间隔秒数，实际按上下百分之二十随机
 * @param speakPermission 群发言权限：1=不操作，2=禁言，3=不禁言
 * @param builderExitEnabled 建群完成后建群账号是否退出群组
 * @param remark 任务备注，可空
 * @param taskEndAt 任务结束时间（epoch 毫秒），必填且必须晚于当前时间
 */
public record CreateGroupPullMarketingTaskDTO(
        String taskName,
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
        Integer materialEntryIntervalSeconds,
        Integer speakPermission,
        Boolean builderExitEnabled,
        String remark,
        Long taskEndAt) {
}
