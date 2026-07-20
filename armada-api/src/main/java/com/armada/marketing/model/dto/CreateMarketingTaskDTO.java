package com.armada.marketing.model.dto;

import java.math.BigDecimal;
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
 * @param accountGroupSendAt    账号群组发送时间;为空时默认任务开始时间往前 72 小时
 * @param taskStartAt           任务计划开始时间(epoch毫秒)
 * @param taskEndAt             任务计划结束时间(epoch毫秒)
 * @param sendPerRound          单轮发送条数
 * @param accountGroupSendIntervalSeconds 单账号下群组发送间隔秒数
 * @param sendIntervalSeconds   发送间隔秒数
 * @param onlineCheckEnabled    发送前是否检测账号在线
 * @param abnormalGroupSkipped  是否跳过异常群
 * @param autoRetryEnabled      失败是否自动重试
 * @param remark                备注
 * @param selections            账号维度目标选择;每个账号可按固定群组或账号动态群组发送
 */
public record CreateMarketingTaskDTO(
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        String startMode,
        Long accountGroupSendAt,
        Long taskStartAt,
        Long taskEndAt,
        Integer sendPerRound,
        BigDecimal accountGroupSendIntervalSeconds,
        Integer sendIntervalSeconds,
        Boolean onlineCheckEnabled,
        Boolean abnormalGroupSkipped,
        Boolean autoRetryEnabled,
        String remark,
        List<MarketingSelectionDTO> selections) {

    /**
     * 兼容旧测试和旧调用方。未传计划时间时仍按当前时间立即/待启动语义落库。
     */
    public CreateMarketingTaskDTO(String taskName,
                                  Long accountGroupId,
                                  String accountGroupName,
                                  Long marketingTemplateId,
                                  String marketingTemplateName,
                                  String startMode,
                                  Integer sendPerRound,
                                  BigDecimal accountGroupSendIntervalSeconds,
                                  Integer sendIntervalSeconds,
                                  Boolean onlineCheckEnabled,
                                  Boolean abnormalGroupSkipped,
                                  Boolean autoRetryEnabled,
                                  String remark,
                                  List<MarketingSelectionDTO> selections) {
        this(taskName, accountGroupId, accountGroupName, marketingTemplateId, marketingTemplateName, startMode,
                null, null, null, sendPerRound, accountGroupSendIntervalSeconds, sendIntervalSeconds,
                onlineCheckEnabled, abnormalGroupSkipped, autoRetryEnabled, remark, selections);
    }
}
