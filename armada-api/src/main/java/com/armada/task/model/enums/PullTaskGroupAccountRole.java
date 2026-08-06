package com.armada.task.model.enums;

/** 执行行内的账号角色；与 pull_task_group_account.role_type 一一对应。 */
public enum PullTaskGroupAccountRole {

    /** 管理账号：每条执行行从冻结的管理账号中选择，踩链接进群后负责邀请拉手。 */
    MANAGER(1),
    /** 拉手：负责批量把站台和料子加入群；跨任务互斥。 */
    PULLER(2),
    /** 站台：每次拉人调用叠加的陪跑账号；同群只入一次，可跨执行行复用。 */
    STATION(3),
    /** 提权管理员：群内既有的我方群主或管理员，仅负责把任务管理员设为管理员。 */
    PROMOTER(4);

    private final int code;

    PullTaskGroupAccountRole(int code) {
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
