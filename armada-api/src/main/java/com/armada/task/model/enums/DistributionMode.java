package com.armada.task.model.enums;

/**
 * 进群任务分配方式码常量。
 */
public final class DistributionMode {

    private DistributionMode() {
        throw new AssertionError("No instances");
    }

    /** 每条群链接分配固定数量账号进群。 */
    public static final String FIXED_ACCOUNTS_PER_LINK = "FIXED_ACCOUNTS_PER_LINK";

    /** 固定账号分配多条群链接进群。 */
    public static final String FIXED_ACCOUNT_MULTI_LINK = "FIXED_ACCOUNT_MULTI_LINK";
}
