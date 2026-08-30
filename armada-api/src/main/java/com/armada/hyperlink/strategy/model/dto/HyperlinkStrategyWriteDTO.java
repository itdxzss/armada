package com.armada.hyperlink.strategy.model.dto;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;

/** 创建与完整更新共用的策略业务字段视图。 */
public interface HyperlinkStrategyWriteDTO {

    String name();
    String taskMode();
    HyperlinkAccountFilterDTO accountFilter();
    Integer maxExecutingAccounts();
    Integer maxUseAccounts();
    Integer maxSendPerAccount();
    Integer cycleIntervalMinutes();
    Boolean enabled();
}
