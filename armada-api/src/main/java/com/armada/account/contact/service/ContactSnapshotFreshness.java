package com.armada.account.contact.service;

/**
 * 通讯录快照新鲜度判定。
 *
 * <p>纯函数工具类，不持有状态，供同步服务与后续任务展开共用同一套口径。</p>
 */
public final class ContactSnapshotFreshness {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private ContactSnapshotFreshness() {
    }

    /**
     * 判断快照是否已过期需要重拉。
     *
     * @param lastSyncedAt 最近一次成功同步时间（epoch 毫秒），从未成功为 null
     * @param now 当前时间（epoch 毫秒）
     * @param ttlHours 快照有效期小时数；小于等于 0 表示每次都重拉
     * @return 需要重拉则 true
     */
    public static boolean isStale(Long lastSyncedAt, long now, int ttlHours) {
        if (lastSyncedAt == null || ttlHours <= 0) {
            return true;
        }
        long age = now - lastSyncedAt;
        // 时钟漂移导致 age 为负时视为新鲜，避免无意义重拉。
        if (age < 0) {
            return false;
        }
        return age >= ttlHours * MILLIS_PER_HOUR;
    }
}
