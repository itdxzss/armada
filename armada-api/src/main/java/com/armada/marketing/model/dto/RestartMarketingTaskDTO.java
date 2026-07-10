package com.armada.marketing.model.dto;

/**
 * 已结束营销任务重新启动入参。
 *
 * @param taskStartAt 新任务开始时间(epoch毫秒)
 * @param taskEndAt   新任务结束时间(epoch毫秒)
 */
public record RestartMarketingTaskDTO(Long taskStartAt, Long taskEndAt) {
}
