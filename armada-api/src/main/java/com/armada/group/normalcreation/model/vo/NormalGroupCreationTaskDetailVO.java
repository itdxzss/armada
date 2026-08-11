package com.armada.group.normalcreation.model.vo;

import java.util.List;

/**
 * 新建普群任务及全部计划群明细。
 *
 * <p>{@code contactFailures} 只包含存在未成功加好友方向的成员，按 {@code itemId} 归属到计划群；
 * 加好友全部成功时为空列表。</p>
 */
public record NormalGroupCreationTaskDetailVO(
        NormalGroupCreationTaskVO task,
        List<NormalGroupCreationItemVO> items,
        List<NormalGroupCreationContactFailureVO> contactFailures) {
}
