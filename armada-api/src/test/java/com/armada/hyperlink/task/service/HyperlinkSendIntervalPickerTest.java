package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 超链逐条发送间隔必须稳定覆盖完整配置区间。 */
class HyperlinkSendIntervalPickerTest {

    @Test
    void consecutiveRecipientsAreEvenlySpreadAcrossTheConfiguredRange() {
        HyperlinkTask task = task(1_000, 5_000);
        int[] bucketCounts = new int[4];

        for (long recipientId = 1; recipientId <= 400; recipientId++) {
            int intervalMs = HyperlinkSendIntervalPicker.pickMs(task, recipientId);

            assertThat(intervalMs).isBetween(1_000, 5_000);
            int bucket = Math.min((intervalMs - 1_000) / 1_001, bucketCounts.length - 1);
            bucketCounts[bucket]++;
        }

        assertThat(Arrays.stream(bucketCounts).min().orElseThrow()).isGreaterThanOrEqualTo(70);
        assertThat(Arrays.stream(bucketCounts).max().orElseThrow()).isLessThanOrEqualTo(130);
    }

    @Test
    void theSameRecipientKeepsTheSameIntervalAcrossRetries() {
        HyperlinkTask task = task(1_000, 5_000);

        int first = HyperlinkSendIntervalPicker.pickMs(task, 13L);
        int retried = HyperlinkSendIntervalPicker.pickMs(task, 13L);

        assertThat(retried).isEqualTo(first);
    }

    @Test
    void equalBoundsKeepTheConfiguredFixedInterval() {
        assertThat(HyperlinkSendIntervalPicker.pickMs(task(2_500, 2_500), 13L))
                .isEqualTo(2_500);
    }

    private static HyperlinkTask task(int minMs, int maxMs) {
        HyperlinkTask task = new HyperlinkTask();
        task.setMsgIntervalMinMs(minMs);
        task.setMsgIntervalMaxMs(maxMs);
        return task;
    }
}
