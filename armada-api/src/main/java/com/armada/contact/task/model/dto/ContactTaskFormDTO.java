package com.armada.contact.task.model.dto;

import java.math.BigDecimal;

/**
 * 通讯录营销任务创建与编辑的统一表单。
 *
 * @param name 任务名称，最长 128
 * @param messageType 消息类型：0 链接消息 / 1 图文消息
 * @param title 消息标题，仅链接消息必填，最长 512
 * @param description 链接描述，仅链接消息必填，最长 2048
 * @param promotionLink 推广链接，仅链接消息必填，最长 2048
 * @param content 正文内容或图文文案，必填，最长 2000
 * @param msgIntervalMinSec 单号发送最小间隔秒，带一位小数
 * @param msgIntervalMaxSec 单号发送最大间隔秒，带一位小数
 * @param concurrency 最大执行账号数，1~200
 * @param maxSendsPerAccount 每号最大发送数，0 表示全部联系人
 * @param retryMax 单条消息失败最大重试次数，0~10
 * @param startMode 启动方式：now 立即 / scheduled 延后
 * @param taskDelayMinutes 延后分钟数，now 模式恒为 0
 * @param isEnabled 任务开关：0 已停用仅保存 / 1 启用
 * @param accountFilterJson 账号筛选条件 JSON 字符串
 */
public record ContactTaskFormDTO(
        String name,
        Integer messageType,
        String title,
        String description,
        String promotionLink,
        String content,
        BigDecimal msgIntervalMinSec,
        BigDecimal msgIntervalMaxSec,
        Integer concurrency,
        Integer maxSendsPerAccount,
        Integer retryMax,
        String startMode,
        Integer taskDelayMinutes,
        Integer isEnabled,
        String accountFilterJson
) {
}
