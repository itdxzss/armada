package com.armada.contact.task.model.vo;

import java.util.List;

/** 任务同一读快照的执行状态、消息效果及账号摘要；原因分组仅详情接口提供。 */
public record ContactTaskStatsVO(Long taskId, Integer runStatus, ContactTaskMetricsVO metrics,
        ContactTaskAccountSummaryVO accounts, List<ContactTaskReasonVO> reasons) {
}
