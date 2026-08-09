package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 普通链接任务结果保护窗口与扫描周期配置测试。 */
class PullTaskExecutionDispatchPropertiesTest {

    @Test
    void rosterReconciliationDefaultsToSixtySecondProtectionAndThirtySecondCadence() {
        PullTaskExecutionDispatchProperties properties =
                new PullTaskExecutionDispatchProperties();

        assertThat(properties.getResultReconciliationDelayMs()).isEqualTo(60_000L);
        assertThat(properties.getResultReconciliationIntervalMs()).isEqualTo(30_000L);
        assertThat(properties.getResultReconciliationDelayMs()
                + properties.getResultReconciliationIntervalMs()).isEqualTo(90_000L);
    }

    @Test
    void rosterReconciliationDurationsMustBePositive() {
        PullTaskExecutionDispatchProperties properties =
                new PullTaskExecutionDispatchProperties();

        assertThatThrownBy(() -> properties.setResultReconciliationDelayMs(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setResultReconciliationIntervalMs(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
