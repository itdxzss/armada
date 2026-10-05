package com.armada.task.model;

import com.armada.task.model.entity.PullTaskAccountAction;

/** 联系人、邀请和踩链接命令共用的尝试序号口径。 */
public final class PullTaskActionAttemptPolicy {

    private static final int FIRST_ATTEMPT = 1;

    private PullTaskActionAttemptPolicy() {
    }

    /** 旧版单次命令存储为 0，协议已按第 1 次执行；新命令使用真实递增序号。 */
    public static int protocolAttempt(PullTaskAccountAction action) {
        return Math.max(FIRST_ATTEMPT, action.getAttemptNo() == null ? 0 : action.getAttemptNo());
    }
}
