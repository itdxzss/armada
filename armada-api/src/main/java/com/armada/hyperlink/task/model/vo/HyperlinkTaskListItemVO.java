package com.armada.hyperlink.task.model.vo;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import java.util.List;

/** 超链任务 H1 列表项；比率由前端按公共公式计算，终态结束时间取运行态真实落库值。 */
public record HyperlinkTaskListItemVO(
        long id,
        String taskName,
        int messageType,
        String taskMode,
        boolean enabled,
        int runStatus,
        HyperlinkProvisionStatus provisionStatus,
        boolean shortLinkEnabled,
        int version,
        String promotionLink,
        Long dataPackageId,
        String dataPackageName,
        HyperlinkAccountFilterDTO accountFilter,
        List<String> targetCountryIso2s,
        Long plannedEndAt,
        int cycleIntervalMinutes,
        long createdAt,
        int recipientTotal,
        long sendTotal,
        long successNum,
        long deliveredNum,
        long readNum,
        long failedNum,
        long unregisteredNum,
        int usedAccountCount,
        int invalidAccountCount,
        int clickUvNum,
        long clickTotal,
        int actualConcurrency,
        long executionDurationSec,
        Long finishedAt,
        Long metricsUpdatedAt) {
}
