package com.armada.account.job;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.account.service.impl.AccountOperationRestrictionServiceImpl;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AccountOperationRestrictionRecoverySchedulerTest {

    @Test
    void tickRestoresExpiredRestrictions() {
        AccountOperationRestrictionService service = mock(AccountOperationRestrictionService.class);

        new AccountOperationRestrictionRecoveryScheduler(service).tick();

        verify(service).restoreExpired(anyLong());
    }

    @Test
    void recoveryContinuesInBatchesOfFiveHundredUntilLastPartialBatch() {
        AccountStateMapper mapper = mock(AccountStateMapper.class);
        when(mapper.restoreExpiredAccountOperationRestrictions(9_000L, 500))
                .thenReturn(500, 37);

        int restored = new AccountOperationRestrictionServiceImpl(mapper)
                .restoreExpired(9_000L);

        Assertions.assertThat(restored).isEqualTo(537);
        verify(mapper, times(2))
                .restoreExpiredAccountOperationRestrictions(9_000L, 500);
    }
}
