package com.armada.hyperlink.strategy.model.dto;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;

/**
 * 超链策略完整创建请求。
 *
 * @param name 策略名称
 * @param taskMode 任务模式：instant、rolling、cycle
 * @param accountFilter 账号筛选合同
 * @param maxExecutingAccounts 最大执行账号数，1 到 100
 * @param maxUseAccounts 最大使用账号数，0 表示不限
 * @param maxSendPerAccount 单账号最大发送数，0 表示不限
 * @param cycleIntervalMinutes 周期任务间隔分钟，非周期保存为 0
 * @param enabled 是否启用
 */
public record HyperlinkStrategyCreateDTO(
        String name,
        String taskMode,
        HyperlinkAccountFilterDTO accountFilter,
        Integer maxExecutingAccounts,
        Integer maxUseAccounts,
        Integer maxSendPerAccount,
        Integer cycleIntervalMinutes,
        Boolean enabled) implements HyperlinkStrategyWriteDTO {
}
