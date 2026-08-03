package com.armada.task.model.enums;

/** 角色账号在本执行行中的可用性；与 pull_task_group_account.availability_status 一一对应。 */
public enum PullTaskGroupAccountAvailability {

    /** 可用：可参与调度。 */
    AVAILABLE(1),
    /** 风控冷却：到期后必须先通过真实可用性校验才能重新可用，到期本身不代表健康。 */
    RISK_COOLDOWN(2),
    /** 离线或不可用：账号级异常，跳过后继续轮询本行其他账号。 */
    OFFLINE(3),
    /** 已移出本行：不再参与本执行行的任何动作。 */
    REMOVED(4);

    private final int code;

    PullTaskGroupAccountAvailability(int code) {
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
