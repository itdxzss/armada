package com.armada.task.model.dto;

/** 计划行(账号×链接一行),由 PlanRowGenerator 产出,落 join_task_result。 */
public record PlanRow(
        /** 账号 id,无效链接行为 null。 */
        Long accountId,
        /** 账号号码快照。 */
        String account,
        /** 群链接。 */
        String link,
        /** 结果码 PENDING / FAILED。 */
        String status,
        /** 失败原因。 */
        String reason) {
}
