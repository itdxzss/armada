package com.armada.group.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.scheduler.GroupBatchTaskExecutors;
import com.armada.group.scheduler.GroupBatchTaskJobProperties;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/**
 * 批量刷新执行器装配测试。
 *
 * <p>锁住只在启动时才暴露的事:属性类必须被注册，否则 GroupBatchTaskJob 构造不出来、
 * 应用直接起不来（单测手工 new 抓不到）。</p>
 */
@SpringJUnitConfig(GroupBatchTaskExecutorConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupBatchTaskExecutorConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void propertiesBeanIsRegisteredSoTheSchedulerCanBeConstructed() {
        assertThat(context.getBeansOfType(GroupBatchTaskJobProperties.class)).hasSize(1);
    }

    @Test
    void bothExecutorsAreWiredAsDistinctPools() {
        GroupBatchTaskExecutors executors = context.getBean(GroupBatchTaskExecutors.class);

        assertThat(executors.task()).isNotNull();
        assertThat(executors.item()).isNotNull();
        // 明细层混用任务层线程池会让并发被 max 2 卡住。
        assertThat(executors.item()).isNotSameAs(executors.task());
        assertThat(context.getBean("groupBatchItemExecutor", Executor.class))
                .isSameAs(executors.item());
    }
}
