package com.armada.task.model.enums;

/** 异常批次成员名单核实状态；CLAIMED 是跨实例至多一次读取门禁。 */
public enum PullTaskPullCallRosterCheckStatus {

    /** 尚未认领名单核实。 */
    NOT_STARTED(0),

    /** 已持久化认领，不得退回未开始。 */
    CLAIMED(1),

    /** 名单读取与本地比对成功。 */
    SUCCEEDED(2),

    /** 名单读取失败，未知号码关闭为最终 UNKNOWN。 */
    FAILED(3),

    /** 没有可用查询账号，跳过查询并关闭为最终 UNKNOWN。 */
    SKIPPED(4);

    private final int code;

    PullTaskPullCallRosterCheckStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
