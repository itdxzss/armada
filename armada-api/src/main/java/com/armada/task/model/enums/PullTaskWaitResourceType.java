package com.armada.task.model.enums;

/** 执行行资源等待类型；与 pull_task_group_execution.wait_resource_type 一一对应，非资源等待时为 null。 */
public enum PullTaskWaitResourceType {

    /** 等待管理员：本行可用管理账号降为 0。 */
    MANAGER(1),
    /** 等待拉手：本行可用拉手降为 0。 */
    PULLER(2),
    /** 等待站台：本次调用可分配站台不足配置数量。 */
    STATION(3),
    /** 等待目标群管理员审批已提交的管理员入群申请。 */
    APPROVAL(4);

    private final int code;

    PullTaskWaitResourceType(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
