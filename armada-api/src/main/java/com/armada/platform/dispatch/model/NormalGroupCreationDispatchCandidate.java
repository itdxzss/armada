package com.armada.platform.dispatch.model;

/** 新建普群跨租户调度扫描的最小只读投影。 */
public record NormalGroupCreationDispatchCandidate(
        Long tenantId,
        Long taskId,
        Long itemId,
        Long creatorAccountId,
        String currentStep,
        String dispatchStage,
        String dispatchStatus) {
}
