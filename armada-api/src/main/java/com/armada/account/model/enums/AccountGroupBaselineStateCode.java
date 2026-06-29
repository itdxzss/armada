package com.armada.account.model.enums;

/**
 * 账号群基线状态码。
 *
 * <p>对应 account.group_baseline_state。账号上线前群基线由上线流程写入,
 * 定时账号群同步只读取该状态,不得覆盖 baseline 快照。</p>
 */
public final class AccountGroupBaselineStateCode {

    private AccountGroupBaselineStateCode() {
    }

    /** 待拍登录前群基线。 */
    public static final int PENDING = 1;

    /** 已拍登录前群基线,可按 baseline 做差集展示上线后的群。 */
    public static final int CAPTURED = 2;

    /** 不启用 baseline 过滤。 */
    public static final int DISABLED = 3;
}
