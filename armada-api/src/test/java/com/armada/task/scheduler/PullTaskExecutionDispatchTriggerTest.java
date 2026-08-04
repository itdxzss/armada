package com.armada.task.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PullTaskExecutionDispatchTriggerTest {

    @Test
    void triggerWaitsUntilCurrentTransactionCommits() {
        PullTaskExecutionDispatchScheduler scheduler =
                mock(PullTaskExecutionDispatchScheduler.class);
        PullTaskExecutionDispatchTrigger trigger =
                new PullTaskExecutionDispatchTrigger(scheduler);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource()));

        transaction.executeWithoutResult(status -> {
            trigger.dispatchAfterCommit();
            verifyNoInteractions(scheduler);
        });

        verify(scheduler).trigger();
    }

    @Test
    void triggerRunsImmediatelyWithoutTransaction() {
        PullTaskExecutionDispatchScheduler scheduler =
                mock(PullTaskExecutionDispatchScheduler.class);
        PullTaskExecutionDispatchTrigger trigger =
                new PullTaskExecutionDispatchTrigger(scheduler);

        trigger.dispatchAfterCommit();

        verify(scheduler).trigger();
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pull_task_execution_trigger_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
