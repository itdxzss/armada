package com.armada.task.model.enums;

/** 带 A/a 标识料子的提权结果；与 pull_task_material_member.admin_status 一一对应。 */
public enum PullTaskMaterialAdminStatus {

    /** 不需要：号码未带 A/a 标识。 */
    NOT_REQUIRED(0),
    /** 待执行：已成功入群且校验在群，等待按设置时机提权。 */
    PENDING(1),
    /** 已提交：提权命令已发出。 */
    SUBMITTED(2),
    /** 成功：已确认取得群管理员权限。 */
    SUCCESS(3),
    /** 失败：提权失败，不反向修改该号码的入群成功结果。 */
    FAILED(4),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(5),
    /** 取消：任务结束时尚未发出的提权动作。 */
    CANCELED(6);

    private final int code;

    PullTaskMaterialAdminStatus(int code) {
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
