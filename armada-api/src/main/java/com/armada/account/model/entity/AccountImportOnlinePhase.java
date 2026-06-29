package com.armada.account.model.entity;

/**
 * 账号导入明细上线阶段常量。
 *
 * <p>对应 {@code account_import_detail.online_phase} 存储编码。</p>
 */
public final class AccountImportOnlinePhase {

    /** 跳过或不参与自动上线。 */
    public static final int SKIPPED = 0;

    /** 导入成功,等待后台派发上线命令。 */
    public static final int QUEUED = 1;

    /** 上线命令已派发,等待协议状态事件回写。 */
    public static final int DISPATCHED = 2;

    /** 本次导入登录结果已冻结。 */
    public static final int SETTLED = 3;

    private AccountImportOnlinePhase() {
    }
}
