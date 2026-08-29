package com.armada.hyperlink.task.model.dto;

import java.math.BigDecimal;

/** H2/H3 唯一任务保存请求。 */
public record HyperlinkTaskSaveDTO(
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
}
