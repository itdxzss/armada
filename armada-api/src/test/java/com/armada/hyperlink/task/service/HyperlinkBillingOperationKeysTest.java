package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 编辑重建的外部钱包幂等键必须隔离旧预约、任务和配置版本。 */
class HyperlinkBillingOperationKeysTest {

    @Test
    void operationKeyChangesWithExternalReservationTaskAndVersion() {
        String baseline = HyperlinkBillingOperationKeys.create("adjust", "external-1", 11L, 3);

        assertThat(HyperlinkBillingOperationKeys.create("adjust", "external-2", 11L, 3))
                .isNotEqualTo(baseline);
        assertThat(HyperlinkBillingOperationKeys.create("adjust", "external-1", 12L, 3))
                .isNotEqualTo(baseline);
        assertThat(HyperlinkBillingOperationKeys.create("adjust", "external-1", 11L, 4))
                .isNotEqualTo(baseline);
        assertThat(HyperlinkBillingOperationKeys.create("adjust", "external-1", 11L, 3))
                .isEqualTo(baseline)
                .hasSizeLessThan(128);
    }
}
