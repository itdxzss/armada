package com.armada.task.model.enums;

/** 普通拉群成员查询用途；用于结果回写后选择唤醒路径。 */
public enum PullTaskMemberQueryPurpose {
    MANAGER_JOIN_MEMBERSHIP(false),
    MANAGER_ADMIN_MEMBERSHIP(false),
    MANAGER_ADMIN_DISCOVERY(false),
    SUPPLEMENT_PULLER_MEMBERSHIP(false),
    SUPPLEMENT_MANAGER_MEMBERSHIP(false),
    PULL_CALL_RECONCILIATION(true),
    UNKNOWN_RESULT_RECONCILIATION(true);

    private final boolean reconciliation;

    PullTaskMemberQueryPurpose(boolean reconciliation) {
        this.reconciliation = reconciliation;
    }

    public boolean reconciliation() {
        return reconciliation;
    }
}
