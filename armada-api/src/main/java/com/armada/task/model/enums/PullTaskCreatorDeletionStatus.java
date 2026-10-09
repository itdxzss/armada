package com.armada.task.model.enums;

/** 永久注销账本状态；非 COMPLETE 状态均禁止放行拉人。 */
public enum PullTaskCreatorDeletionStatus {
    /** 冻结了建群者，尚未授权发送删除。 */
    RESERVED(0),
    /** 唯一发送意图已持久化；重启后只允许 GET。 */
    SUBMITTED(1),
    /** 删除已获得匹配结果，仍等待独立清理证明。 */
    ACCEPTED(2),
    /** 发送或结果未知；只能继续查询对账。 */
    UNKNOWN(3),
    /** 明确失败；暂停，禁止自动重新注销。 */
    FAILED(4),
    /** 删除、注册状态、创建者和管理员证明全部满足。 */
    COMPLETE(5),
    /** 执行终态且注销未提交，预留已释放；不代表账号曾被注销。 */
    RELEASED(6);

    private final int code;
    PullTaskCreatorDeletionStatus(int code) { this.code = code; }
    /** 数据库存储值。 */
    public int code() { return code; }
}
