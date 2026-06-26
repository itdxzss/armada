package com.armada.task.model.dto;

/** 计划行(账号×链接一行),由 PlanRowGenerator 产出,落 join_task_result。 */
public record PlanRow(Long accountId, String account, String link, String status, String reason) {
}
