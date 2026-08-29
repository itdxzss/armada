package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.service.ContactSnapshotFreshness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactSnapshotFreshnessTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_756_345_678_901L;

    @Test
    void neverSyncedIsAlwaysStale() {
        assertThat(ContactSnapshotFreshness.isStale(null, NOW, 24)).isTrue();
    }

    @Test
    void snapshotInsideTtlIsFresh() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 23 * HOUR, NOW, 24)).isFalse();
    }

    @Test
    void snapshotExactlyAtTtlIsStale() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 24 * HOUR, NOW, 24)).isTrue();
    }

    @Test
    void snapshotBeyondTtlIsStale() {
        assertThat(ContactSnapshotFreshness.isStale(NOW - 25 * HOUR, NOW, 24)).isTrue();
    }

    @Test
    void nonPositiveTtlForcesEveryReadToRefetch() {
        assertThat(ContactSnapshotFreshness.isStale(NOW, NOW, 0)).isTrue();
        assertThat(ContactSnapshotFreshness.isStale(NOW, NOW, -1)).isTrue();
    }

    @Test
    void clockSkewFromTheFutureCountsAsFresh() {
        // 多节点时钟漂移导致快照时间晚于 now，不应触发无意义重拉
        assertThat(ContactSnapshotFreshness.isStale(NOW + HOUR, NOW, 24)).isFalse();
    }

    @Test
    void propertiesFallBackToSaneDefaults() {
        AccountContactProperties unset = new AccountContactProperties(null);
        assertThat(unset.snapshotTtlHoursOrDefault()).isEqualTo(24);

        AccountContactProperties set = new AccountContactProperties(6);
        assertThat(set.snapshotTtlHoursOrDefault()).isEqualTo(6);
    }
}
