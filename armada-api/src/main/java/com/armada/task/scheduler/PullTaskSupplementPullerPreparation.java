package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskSupplementPullerWork;

/** 补充拉手踩链接短事务的准备结果。 */
public record PullTaskSupplementPullerPreparation(
        boolean handled,
        PullTaskSupplementPullerWork work,
        PullTaskExecutionDispatchResult result) {

    public static PullTaskSupplementPullerPreparation notHandled() {
        return new PullTaskSupplementPullerPreparation(false, null, null);
    }

    public static PullTaskSupplementPullerPreparation ready(
            PullTaskSupplementPullerWork work) {
        return new PullTaskSupplementPullerPreparation(true, work, null);
    }

    public static PullTaskSupplementPullerPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskSupplementPullerPreparation(true, null, result);
    }

    public boolean ready() {
        return work != null;
    }
}
