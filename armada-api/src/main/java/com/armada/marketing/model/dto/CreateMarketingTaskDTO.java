package com.armada.marketing.model.dto;

import java.util.List;

/**
 * 新建群组营销任务入参。字段名沿用 armada 当前 camelCase JSON 口径。
 *
 * @param taskName              任务名称
 * @param accountGroupId        账号分组 ID
 * @param accountGroupName      账号分组名称快照
 * @param marketingTemplateId   营销模板 ID
 * @param marketingTemplateName 营销模板名称快照
 * @param startMode             PENDING/待启动 或 IMMEDIATE/立即启动
 * @param sendPerRound          单轮发送条数
 * @param sendIntervalSeconds   发送间隔秒数
 * @param onlineCheckEnabled    发送前是否检测账号在线
 * @param abnormalGroupSkipped  是否跳过异常群
 * @param autoRetryEnabled      失败是否自动重试
 * @param remark                备注
 * @param selections            账号→群组选择
 */
public record CreateMarketingTaskDTO(
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        String startMode,
        Integer sendPerRound,
        Integer sendIntervalSeconds,
        Boolean onlineCheckEnabled,
        Boolean abnormalGroupSkipped,
        Boolean autoRetryEnabled,
        String remark,
        List<MarketingSelectionDTO> selections) {
}
