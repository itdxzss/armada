package com.armada.contact.task.model.vo;

import java.math.BigDecimal;

/**
 * 通讯录营销任务列表行。
 *
 * @param id 任务 ID
 * @param name 任务名称
 * @param messageType 消息类型：0 链接消息 / 1 图文消息
 * @param title 消息标题；图文消息为空
 * @param content 正文或图文文案，列表用作内容预览
 * @param promotionLink 推广链接
 * @param accountFilter 账号筛选条件 JSON
 * @param isEnabled 任务开关
 * @param runStatus 运行状态
 * @param totalSendNum 计划发送总条数
 * @param successMessageNum 累计发送确认条数，不代表送达
 * @param usedAccountCount 有收件人的账号数
 * @param invalidAccountNum 任务执行失败账号数，不代表封禁
 * @param avgSendPerAccount 号均发量
 * @param taskStartAt 计划开始时间（epoch 毫秒）
 * @param createdAt 创建时间（epoch 毫秒）
 * @param stats 同一读快照的任务执行和消息效果统计
 */
public record ContactTaskListItemVO(
        Long id,
        String name,
        Integer messageType,
        String title,
        String content,
        String promotionLink,
        String accountFilter,
        Integer isEnabled,
        Integer runStatus,
        Integer totalSendNum,
        Integer successMessageNum,
        Integer usedAccountCount,
        Integer invalidAccountNum,
        BigDecimal avgSendPerAccount,
        Long taskStartAt,
        Long createdAt,
        ContactTaskStatsVO stats
) {
}
