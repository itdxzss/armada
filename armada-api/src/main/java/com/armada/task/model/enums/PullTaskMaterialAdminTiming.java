package com.armada.task.model.enums;

/** 带 A/a 料子的群管理员设置时机。 */
public enum PullTaskMaterialAdminTiming {

    /** 每批成功入群并形成待提权事实后，先提权再继续拉下一批。 */
    IMMEDIATE(1),
    /** 当前执行行全部料子入群终态后统一提权。 */
    AFTER_GROUP_DONE(2);

    private final int code;

    PullTaskMaterialAdminTiming(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
