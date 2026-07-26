package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.scheduler.MarketingRoundWorker;
import com.armada.marketing.service.impl.MarketingImmediateRetryService;
import com.armada.marketing.service.impl.MarketingNewGroupImmediateSendServiceImpl;
import com.armada.marketing.service.impl.MarketingSendResultServiceImpl;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/** 拆分营销结果快照查询与 target 更新后，锁定所有现有调用入口的事务边界。 */
class MarketingResultTransactionBoundaryTest {

    @Test
    void everyCurrentTargetResultRollupEntryPointRemainsTransactional() {
        assertTransactional(MarketingSendResultServiceImpl.class, "handleSendResultReported");
        assertTransactional(MarketingImmediateRetryService.class, "retryIfEligible");
        assertTransactional(MarketingNewGroupImmediateSendServiceImpl.class, "enqueueNewGroups");
        assertTransactional(MarketingNewGroupImmediateSendServiceImpl.class, "enqueueFixedTarget");
        assertTransactional(MarketingRoundWorker.class, "runRound");
    }

    private void assertTransactional(Class<?> type, String methodName) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Transactional annotation = AnnotatedElementUtils.findMergedAnnotation(
                method, Transactional.class);

        assertThat(annotation)
                .as("%s#%s must keep SELECT + UPDATE in one transaction", type.getSimpleName(), methodName)
                .isNotNull();
        assertThat(annotation.rollbackFor()).contains(Exception.class);
    }
}
