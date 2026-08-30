package com.armada.hyperlink.task.model.dto;

import java.math.BigDecimal;

/** H2/H3 唯一任务保存请求。 */
public record HyperlinkTaskSaveDTO(
        Integer version,
        Long sourceTaskId,
        Long sourceStrategyId,
        String taskName,
        Integer messageType,
        HyperlinkTaskMessageContentDTO messageContent,
        String taskMode,
        Long plannedEndAt,
        Integer cycleIntervalMinutes,
        HyperlinkAccountFilterDTO accountFilter,
        BigDecimal messageIntervalMinSeconds,
        BigDecimal messageIntervalMaxSeconds,
        Integer maxExecutingAccounts,
        Integer maxUseAccounts,
        Integer maxSendPerAccount,
        String startMode,
        Integer delayMinutes,
        Long dataPackageId,
        Boolean enabled,
        String quoteToken) {

    /** 兼容既有调用；未显式引用模板时来源策略为空。 */
    public HyperlinkTaskSaveDTO(
            Integer version,
            Long sourceTaskId,
            String taskName,
            Integer messageType,
            HyperlinkTaskMessageContentDTO messageContent,
            String taskMode,
            Long plannedEndAt,
            Integer cycleIntervalMinutes,
            HyperlinkAccountFilterDTO accountFilter,
            BigDecimal messageIntervalMinSeconds,
            BigDecimal messageIntervalMaxSeconds,
            Integer maxExecutingAccounts,
            Integer maxUseAccounts,
            Integer maxSendPerAccount,
            String startMode,
            Integer delayMinutes,
            Long dataPackageId,
            Boolean enabled,
            String quoteToken) {
        this(version, sourceTaskId, null, taskName, messageType, messageContent, taskMode,
                plannedEndAt, cycleIntervalMinutes, accountFilter, messageIntervalMinSeconds,
                messageIntervalMaxSeconds, maxExecutingAccounts, maxUseAccounts,
                maxSendPerAccount, startMode, delayMinutes, dataPackageId, enabled, quoteToken);
    }
}
