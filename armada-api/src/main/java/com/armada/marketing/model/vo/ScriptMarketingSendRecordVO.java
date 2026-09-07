package com.armada.marketing.model.vo;

/** 剧本执行详情。 */
public record ScriptMarketingSendRecordVO(
        Long id,
        Long taskId,
        Long groupId,
        Integer stepIndex,
        Long accountId,
        String commandId,
        Integer status,
        String reason,
        String messageId,
        Long submittedAt,
        Long finishedAt) { }
