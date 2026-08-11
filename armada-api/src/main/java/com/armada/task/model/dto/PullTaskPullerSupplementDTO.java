package com.armada.task.model.dto;

import java.util.List;

/**
 * 用户确认的补充拉手不可变指令。
 *
 * @param accountGroupId 候选拉手账号分组
 * @param supplementCount 本次补充数量
 * @param selectionMode 自动或手动选择
 * @param entryMode 固定为踩链接
 * @param accountIds 手动选择账号；自动选择时必须为空
 */
public record PullTaskPullerSupplementDTO(
        Long accountGroupId,
        Integer supplementCount,
        Integer selectionMode,
        Integer entryMode,
        List<Long> accountIds) {

    /** 固化请求中的选号顺序，避免保存前被外部修改。 */
    public PullTaskPullerSupplementDTO {
        accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    }
}
