package com.armada.task.model.dto;

/** 保存租户拉群营销全局设置的请求。 */
public record PullTaskGroupMarketingSettingDTO(
        Integer marketingSilenceMinutes,
        Integer groupLockdownMinutes,
        Integer maxMarketingAccountsPerGroup
) {
}
