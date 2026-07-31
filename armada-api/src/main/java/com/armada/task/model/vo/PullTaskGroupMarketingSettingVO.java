package com.armada.task.model.vo;

/** 租户拉群营销全局设置响应；未配置时三个业务值均为 {@code null}。 */
public record PullTaskGroupMarketingSettingVO(
        boolean configured,
        Integer marketingSilenceMinutes,
        Integer groupLockdownMinutes,
        Integer maxMarketingAccountsPerGroup
) {
}
