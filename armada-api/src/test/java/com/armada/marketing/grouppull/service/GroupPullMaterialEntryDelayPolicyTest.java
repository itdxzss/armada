package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 拉群营销逐料随机间隔策略测试。 */
class GroupPullMaterialEntryDelayPolicyTest {

    @Test
    void normalizesDefaultAndRejectsValuesOutsideWholeMinuteRange() {
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(null)).isEqualTo(300);
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(60)).isEqualTo(60);
        assertThat(GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(3_600)).isEqualTo(3_600);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(59))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(3_601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(301))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculatesInclusiveTwentyPercentWindowAndUsesRandomSecond() {
        GroupPullMaterialEntryDelayPolicy lower =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> origin);
        GroupPullMaterialEntryDelayPolicy upper =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> bound - 1);

        assertThat(lower.delayWindow(300))
                .isEqualTo(new GroupPullMaterialEntryDelayPolicy.DelayWindow(240_000L, 360_000L));
        assertThat(lower.nextExecuteAt(1_000L, 300)).isEqualTo(241_000L);
        assertThat(upper.nextExecuteAt(1_000L, 300)).isEqualTo(361_000L);
    }
}
