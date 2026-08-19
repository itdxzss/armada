package com.armada.marketing.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskMapperTest {

    @Test
    void dueTaskScanIgnoresTenantInterceptorBecauseSchedulerHasNoTenantContext() throws Exception {
        Method method = MarketingTaskMapper.class.getMethod("selectDueSendingTasks", long.class, int.class);

        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

        assertThat(annotation)
                .as("marketing round scheduler scans due tasks before it can restore tenant context")
                .isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }

    @Test
    void waitingAttemptLockUsesExplicitTenantToPreserveMysqlLockingClauseOrder() throws Exception {
        Method method = MarketingTaskMapper.class.getMethod(
                "selectWaitingAttemptsForUpdate", Long.class, Long.class, List.class, long.class);

        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }
}
