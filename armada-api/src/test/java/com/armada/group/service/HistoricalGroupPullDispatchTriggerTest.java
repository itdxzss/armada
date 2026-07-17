package com.armada.group.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 历史群拉人事务提交后派发测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupPullDispatchTriggerTest {

    @Mock
    private HistoricalGroupPullWorker worker;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchesOnlyAfterCommit() {
        HistoricalGroupPullDispatchTrigger trigger =
                new HistoricalGroupPullDispatchTrigger(worker, Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        trigger.dispatchAfterCommit(71L, 901L);

        verify(worker, never()).execute(71L, 901L);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(worker).execute(71L, 901L);
    }
}
