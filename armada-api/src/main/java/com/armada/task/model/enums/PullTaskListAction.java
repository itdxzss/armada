package com.armada.task.model.enums;

/** 拉群任务一级列表当前真实可用的行操作。 */
public enum PullTaskListAction {

    /** 查看任务详情。 */
    DETAIL,

    /** 启动待开始的普通群链接任务。 */
    START,

    /** 暂停执行中的普通群链接任务。 */
    PAUSE,

    /** 恢复人工暂停的普通群链接任务。 */
    RESUME,

    /** 永久结束普通群链接任务。 */
    END,

    /** 按任务类型和状态校验后软删除。 */
    DELETE
}
