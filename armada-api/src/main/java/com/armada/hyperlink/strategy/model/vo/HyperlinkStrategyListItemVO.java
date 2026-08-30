package com.armada.hyperlink.strategy.model.vo;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;

/**
 * 超链策略分页列表项。
 *
 * @param id 策略 ID
 * @param name 策略名称
 * @param taskMode 任务模式
 * @param accountFilter 账号筛选合同
 * @param maxExecutingAccounts 最大执行账号数
 * @param maxUseAccounts 最大使用账号数
 * @param maxSendPerAccount 单账号最大发送数
 * @param cycleIntervalMinutes 周期间隔分钟
 * @param enabled 是否启用
 * @param version 乐观锁版本
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record HyperlinkStrategyListItemVO(
        Long id,
        String name,
        String taskMode,
        HyperlinkAccountFilterDTO accountFilter,
        Integer maxExecutingAccounts,
        Integer maxUseAccounts,
        Integer maxSendPerAccount,
        Integer cycleIntervalMinutes,
        boolean enabled,
        Integer version,
        Long createdBy,
        Long createdAt,
        Long updatedAt) {
}
