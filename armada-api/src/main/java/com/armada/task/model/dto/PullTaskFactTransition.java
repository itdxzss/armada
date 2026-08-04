package com.armada.task.model.dto;

import java.util.List;

/**
 * 外部协议事实的条件更新参数；允许状态由业务层传入，Mapper 不固化状态机。
 *
 * @param id               事实行主键
 * @param expectedStatuses 允许的原状态，非空
 * @param targetStatus     目标状态
 * @param result           原因、成员 JID 与事实发生时间
 * @param now              本次更新时间(epoch 毫秒)
 */
public record PullTaskFactTransition(
        long id,
        List<Integer> expectedStatuses,
        int targetStatus,
        PullTaskFactResult result,
        long now) {

    /** 固化状态集合，避免执行 SQL 前被外部修改。 */
    public PullTaskFactTransition {
        expectedStatuses = List.copyOf(expectedStatuses);
        if (expectedStatuses.isEmpty()) {
            throw new IllegalArgumentException("允许的原状态不能为空");
        }
        if (result == null) {
            result = PullTaskFactResult.empty();
        }
    }
}
