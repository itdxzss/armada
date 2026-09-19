package com.armada.task.model.enums;

/** 执行行角色账号的冻结来源。 */
public enum PullTaskGroupAccountSource {

    /** 任务执行链路初次选择。 */
    INITIAL(1),
    /** 执行行后续补充；由 selectionMode 区分自动替补与人工选择。 */
    SUPPLEMENT(2);

    private final int code;

    PullTaskGroupAccountSource(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
