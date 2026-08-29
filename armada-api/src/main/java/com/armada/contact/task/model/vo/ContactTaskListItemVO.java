package com.armada.contact.task.model.vo;

import java.math.BigDecimal;

/**
 * 通讯录营销任务列表行。
 *
 * @param id 任务 ID
 * @param name 任务名称
 * @param messageType 消息类型：0 链接消息 / 1 图文消息
 * @param title 消息标题
 * @param promotionLink 推广链接
 * @param accountFilter 账号筛选条件 JSON
 * @param isEnabled 任务开关
 * @param runStatus 运行状态
 * @param totalSendNum 计划发送总条数
 * @param successMessageNum 成功送达条数
 * @param usedAccountCount 使用号数
 * @param invalidAccountNum 封号数
 * @param avgSendPerAccount 号均发量
 * @param taskStartAt 计划开始时间（epoch 毫秒）
 * @param createdAt 创建时间（epoch 毫秒）
 */
public record ContactTaskListItemVO(
        Long id,
        String name,
        Integer messageType,
        String title,
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
        Long createdAt
) {
}
