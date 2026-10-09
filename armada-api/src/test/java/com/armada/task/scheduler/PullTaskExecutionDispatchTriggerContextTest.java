package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 调度器恢复链回到唤醒入口时，容器仍能启动且唤醒保持在事务提交之后。 */
class PullTaskExecutionDispatchTriggerContextTest {
    @Test
    void startsWithSchedulerBackReferenceAndDispatchesOnlyAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowCircularReferences(false);
            context.register(Config.class);
            assertThatCode(context::refresh).doesNotThrowAnyException();
            var trigger = context.getBean(PullTaskExecutionDispatchTrigger.class);
            var scheduler = context.getBean(PullTaskExecutionDispatchScheduler.class);
            TransactionSynchronizationManager.initSynchronization();
            try {
                trigger.dispatchAfterCommit();
                verify(scheduler, never()).trigger();
                TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
                verify(scheduler).trigger();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PullTaskExecutionDispatchTrigger.class)
    static class Config {
        /** 模拟调度器通过恢复链反向依赖唤醒入口；不启动调度线程。 */
        @Bean PullTaskExecutionDispatchScheduler scheduler(PullTaskExecutionDispatchTrigger trigger) {
            return mock(PullTaskExecutionDispatchScheduler.class);
        }
    }
}
