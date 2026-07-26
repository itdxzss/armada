package com.armada.marketing.grouppull.model.enums;

/** 拉群营销任务资源状态。 */
public enum GroupPullResourceStatus {

    /** 尚未锁定营销分组。 */
    UNLOCKED(1),

    /** 营销分组及已领取账号处于锁定状态。 */
    LOCKED(2),

    /** 正在安全结束执行并释放资源。 */
    RELEASING(3),

    /** 资源已经全部释放。 */
    RELEASED(4);

    private final int code;

    GroupPullResourceStatus(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析资源状态。 */
    public static GroupPullResourceStatus fromCode(int code) {
        return switch (code) {
            case 1 -> UNLOCKED;
            case 2 -> LOCKED;
            case 3 -> RELEASING;
            case 4 -> RELEASED;
            default -> throw new IllegalArgumentException("未知拉群资源状态: " + code);
        };
    }
}
