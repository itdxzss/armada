package com.armada.hyperlink.task.model.vo;

import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;

/** 创建、编辑、准备轮询和动作的统一回执。 */
public record HyperlinkTaskMutationReceiptVO(
        long taskId,
        HyperlinkProvisionStatus provisionStatus,
        boolean enabled,
        int runStatus,
        int version,
        Long pollAfterMs,
        Integer failureCode,
        String failureReason) {
}
