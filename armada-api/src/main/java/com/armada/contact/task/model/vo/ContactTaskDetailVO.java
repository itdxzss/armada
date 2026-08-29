package com.armada.contact.task.model.vo;

import java.math.BigDecimal;

/**
 * 通讯录营销任务完整详情。
 *
 * @param id 任务 ID
 * @param name 任务名称
 * @param messageType 消息类型：0 链接消息 / 1 图文消息
 * @param title 消息标题
 * @param description 链接描述
 * @param promotionLink 推广链接
 * @param content 正文内容或图文文案
 * @param previewImageFileId 预览图或配图文件 ID
 * @param accountFilter 账号筛选条件 JSON
 * @param msgIntervalMinSec 单号发送最小间隔秒
 * @param msgIntervalMaxSec 单号发送最大间隔秒
 * @param concurrency 最大执行账号数
 * @param maxSendsPerAccount 每号最大发送数
 * @param retryMax 失败重试次数
 * @param startMode 启动方式
 * @param taskDelayMinutes 延后分钟数
 * @param taskStartAt 计划开始时间（epoch 毫秒）
 * @param isEnabled 任务开关
 * @param runStatus 运行状态
 * @param totalSendNum 计划发送总条数
 * @param successMessageNum 成功送达条数
 * @param usedAccountCount 使用号数
 * @param invalidAccountNum 封号数
 * @param avgSendPerAccount 号均发量
 * @param createdAt 创建时间（epoch 毫秒）
 * @param updatedAt 更新时间（epoch 毫秒）
 */
public record ContactTaskDetailVO(
        Long id,
        String name,
        Integer messageType,
        String title,
        String description,
        String promotionLink,
        String content,
        Long previewImageFileId,
        String accountFilter,
        BigDecimal msgIntervalMinSec,
        BigDecimal msgIntervalMaxSec,
        Integer concurrency,
        Integer maxSendsPerAccount,
        Integer retryMax,
        String startMode,
        Integer taskDelayMinutes,
        Long taskStartAt,
        Integer isEnabled,
        Integer runStatus,
        Integer totalSendNum,
        Integer successMessageNum,
        Integer usedAccountCount,
        Integer invalidAccountNum,
        BigDecimal avgSendPerAccount,
        Long createdAt,
        Long updatedAt
) {
}
