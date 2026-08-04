package com.armada.task.model.dto;

import java.util.List;

/** 用户确认的补充站台不可变选择。 */
public record PullTaskStationSupplementDTO(
        Long accountGroupId,
        Integer supplementCount,
        Integer selectionMode,
        List<Long> accountIds) {

    /** 固化手动选号顺序；自动选号保持空列表。 */
    public PullTaskStationSupplementDTO {
        accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    }
}
