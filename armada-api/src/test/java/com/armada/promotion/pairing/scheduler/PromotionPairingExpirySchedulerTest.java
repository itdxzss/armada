package com.armada.promotion.pairing.scheduler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.service.impl.PromotionPairingCompletionService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionPairingExpirySchedulerTest {

    @Mock
    private PromotionPairingSessionMapper sessionMapper;
    @Mock
    private PromotionPairingCompletionService completionService;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void expiresActiveSessionsAndRestoresPreviousTenantContext() {
        PromotionPairingSession session = new PromotionPairingSession();
        session.setId(7001L);
        session.setTenantId(7L);
        when(sessionMapper.selectExpiredActive(anyLong(), eq(100))).thenReturn(List.of(session));
        TenantContext.set(99L);
        PromotionPairingExpiryScheduler scheduler =
                new PromotionPairingExpiryScheduler(sessionMapper, completionService, 100);

        scheduler.expireOnce();

        verify(completionService).expireIfDue(eq(7001L), eq(7L), anyLong());
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }
}
