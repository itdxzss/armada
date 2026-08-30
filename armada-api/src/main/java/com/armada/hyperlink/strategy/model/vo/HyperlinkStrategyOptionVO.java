package com.armada.hyperlink.strategy.model.vo;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;

/**
 * 新建和编辑任务复制策略值时使用的启用候选。
 *
 * @param id 策略 ID，仅用于本次选择
 * @param name 策略名称
 * @param taskMode 任务模式
 * @param accountFilter 账号筛选合同
 * @param maxExecutingAccounts 最大执行账号数
 * @param maxUseAccounts 最大使用账号数
 * @param maxSendPerAccount 单账号最大发送数
 * @param cycleIntervalMinutes 周期间隔分钟
 */
public record HyperlinkStrategyOptionVO(
        Long id,
        String name,
        String taskMode,
        HyperlinkAccountFilterDTO accountFilter,
        Integer maxExecutingAccounts,
        Integer maxUseAccounts,
        Integer maxSendPerAccount,
        Integer cycleIntervalMinutes) {
}
