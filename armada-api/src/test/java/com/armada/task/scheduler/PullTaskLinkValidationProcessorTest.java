package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 部署前已落在旧链接校验阶段的执行行兼容推进测试。 */
class PullTaskLinkValidationProcessorTest {

    @Test
    void legacyStageAdvancesLocallyWithoutAnInvitePageDependency() {
        PullTaskExecutionTransactionService transactions =
                mock(PullTaskExecutionTransactionService.class);
        PullTaskLinkValidationProcessor processor =
                new PullTaskLinkValidationProcessor(transactions);
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        PullTaskExecutionWork work = new PullTaskExecutionWork(
                7L, 11L,
                "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA",
                "AAAAAAAAAAAAAAAAAAAAAA",
                new PullTaskExecutionLease("worker-1", 3));
        when(transactions.prepare(candidate, "worker-1", 700L))
                .thenReturn(Optional.of(work));
        when(transactions.advanceLegacyLinkValidation(work, 700L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 700L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(transactions).advanceLegacyLinkValidation(work, 700L);
    }
}
