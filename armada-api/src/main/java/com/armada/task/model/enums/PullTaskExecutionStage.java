package com.armada.task.model.enums;

/** 群链接执行行业务阶段；与 pull_task_group_execution.stage 的 TINYINT 取值一一对应。 */
public enum PullTaskExecutionStage {

    /** 历史链接校验阶段：旧执行行由调度器在本地直接推进，不再访问公开邀请页。 */
    LINK_VALIDATION(1),
    /** 管理入群：管理分组账号踩链接进入目标群。 */
    MANAGER_JOIN(2),
    /** 管理员设置：由群内现有我方群主或管理员为任务管理员提权并实时确认。 */
    MANAGER_ADMIN(3),
    /** 管理—拉手联系人：双向保存联系人，失败不阻断。 */
    MANAGER_PULLER_CONTACT(4),
    /** 管理邀请拉手：管理账号轮询单人邀请，全局固定 1 秒间隔。 */
    PULLER_INVITE(5),
    /** 拉人执行：含拉手—站台联系人与站台、料子同批加入。 */
    PULL_EXECUTION(6),
    /** 料子提权：给带 A/a 标识且已成功入群的料子设置群管理员。 */
    MATERIAL_ADMIN(7),
    /** 收口：本行全部动作终态，写入完成状态。 */
    CLOSING(8);

    private final int code;

    PullTaskExecutionStage(int code) {
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
