package com.armada.promotion.pairing.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionCapiPrivacyRetentionSchedulerTest {

    @Mock
    private PromotionCapiEventOutboxMapper outboxMapper;

    @Test
    void cleanupRunsIndependentlyWithAStaleLockSafetyWindow() {
        PromotionCapiPrivacyRetentionScheduler scheduler =
                new PromotionCapiPrivacyRetentionScheduler(outboxMapper, 500);

        scheduler.scrubExpiredSensitiveData();

        ArgumentCaptor<Long> now = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> lockedBefore = ArgumentCaptor.forClass(Long.class);
        verify(outboxMapper).scrubExpiredSensitive(now.capture(), lockedBefore.capture(),
                org.mockito.ArgumentMatchers.eq(500));
        assertThat(now.getValue() - lockedBefore.getValue()).isEqualTo(300_000L);
        assertThat(now.getValue()).isPositive();
    }
}
