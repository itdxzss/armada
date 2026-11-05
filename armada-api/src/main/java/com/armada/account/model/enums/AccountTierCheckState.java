package com.armada.account.model.enums;
/** 档位检测状态与方式；和数据库 COMMENT/前端契约一致。 */
public final class AccountTierCheckState {
    private AccountTierCheckState() {}
    /** 自查。 */ public static final int SELF = 1;
    /** 探针代查。 */ public static final int PROBE = 2;
    /** 批次执行中。 */ public static final int RUNNING = 1;
    /** 批次已停止。 */ public static final int STOPPED = 2;
    /** 批次已完成。 */ public static final int COMPLETED = 3;
    /** 批次部分失败/不可查。 */ public static final int PARTIAL = 4;
    /** 待查。 */ public static final int PENDING = 1;
    /** 查询中。 */ public static final int QUERYING = 2;
    /** 成功快照。 */ public static final int SUCCESS = 3;
    /** 失败，可手动重试。 */ public static final int FAILED = 4;
    /** 服务器不可查。 */ public static final int UNQUERYABLE = 5;
    /** 停止取消。 */ public static final int CANCELED = 6;
}
