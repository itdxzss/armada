package com.armada.marketing.model.vo;

/** 剧本执行详情。 */
public record ScriptMarketingGroupVO(
        Long id,
        Long taskId,
        Long groupLinkId,
        String groupJid,
        String groupName,
        Integer nextStep,
        Long nextAt,
        Long remainingWaitMs, String bindingsJson, Boolean paused, String pauseReason) { }
