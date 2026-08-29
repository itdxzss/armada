package com.armada.hyperlink.task.model.vo;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskMessageContentDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** 新建抽屉编辑、查看和复制共用的完整任务详情。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HyperlinkTaskDetailVO(
        long id,
        String taskName,
        int messageType,
        String taskMode,
        boolean enabled,
        int runStatus,
        boolean shortLinkEnabled,
        int version,
        boolean editable,
        HyperlinkTaskMessageContentDTO messageContent,
        Long plannedEndAt,
        int cycleIntervalMinutes,
        HyperlinkAccountFilterDTO accountFilter,
        BigDecimal messageIntervalMinSeconds,
        BigDecimal messageIntervalMaxSeconds,
        int maxExecutingAccounts,
        int maxUseAccounts,
        int maxSendPerAccount,
        String startMode,
        int delayMinutes,
        Long dataPackageId,
        String dataPackageName,
        boolean dataPackageAvailable,
        long createdAt,
        long updatedAt) {
}
