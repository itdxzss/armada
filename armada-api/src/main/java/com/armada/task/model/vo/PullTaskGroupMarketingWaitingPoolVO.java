package com.armada.task.model.vo;

import java.util.List;

/**
 * 当前创建页的等待池快照。
 *
 * @param reservationToken 服务端等待池随机标识
 * @param groups 已成功软占用的群组
 * @param rejected 本次加入时被拒绝的群组及原因
 */
public record PullTaskGroupMarketingWaitingPoolVO(
        String reservationToken,
        List<PullTaskGroupMarketingCandidateVO> groups,
        List<PullTaskGroupMarketingWaitingPoolRejectedVO> rejected) {

    public PullTaskGroupMarketingWaitingPoolVO {
        groups = groups == null ? List.of() : List.copyOf(groups);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }
}
