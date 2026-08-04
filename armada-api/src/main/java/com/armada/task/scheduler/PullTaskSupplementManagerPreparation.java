package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskSupplementManagerWork;

/** 补充管理员短事务的单步准备结果。 */
public record PullTaskSupplementManagerPreparation(
        boolean handled,
        PullTaskSupplementManagerWork work,
        PullTaskExecutionDispatchResult result) {

    /** @return 当前执行行没有人工补充指令，应回退原管理员处理器 */
    public static PullTaskSupplementManagerPreparation notHandled() {
        return new PullTaskSupplementManagerPreparation(false, null, null);
    }

    /** @return 已预写一条事务外协议工作 */
    public static PullTaskSupplementManagerPreparation ready(
            PullTaskSupplementManagerWork work) {
        return new PullTaskSupplementManagerPreparation(true, work, null);
    }

    /** @return 已在事务内完成等待或竞争失败处理 */
    public static PullTaskSupplementManagerPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskSupplementManagerPreparation(true, null, result);
    }

    /** @return 是否有事务外协议工作 */
    public boolean ready() {
        return work != null;
    }
}
