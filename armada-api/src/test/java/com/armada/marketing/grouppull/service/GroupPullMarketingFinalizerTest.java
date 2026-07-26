package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 拉群营销结果结算状态测试。 */
class GroupPullMarketingFinalizerTest {

    @Test
    void failedExecutionKeepsTheStageWhereTheFailureOccurred() {
        AtomicInteger expectedStatus = new AtomicInteger();
        AtomicInteger expectedStage = new AtomicInteger();
        AtomicInteger terminalStatus = new AtomicInteger();
        AtomicInteger terminalStage = new AtomicInteger();
        GroupPullMarketingMapper mapper = mapper((method, args) -> switch (method) {
            case "selectExecutionById" -> execution();
            case "selectTaskById" -> task();
            case "countSuccessfulMaterialEntries" -> 2L;
            case "markExecutionTerminal" -> {
                expectedStatus.set((Integer) args[1]);
                expectedStage.set((Integer) args[2]);
                terminalStatus.set((Integer) args[3]);
                terminalStage.set((Integer) args[4]);
                yield 1;
            }
            case "completeFailedJoinedMaterials", "releaseUnjoinedMaterials",
                    "cancelMarketingQuota", "markExecutionReleased" -> 1;
            default -> throw new UnsupportedOperationException(method);
        });
        GroupPullMarketingFinalizer finalizer = new GroupPullMarketingFinalizer(
                mapper, null, new ReleasingOccupancyService(), null);

        finalizer.finalizeAfterStages(501L);

        assertThat(expectedStatus.get()).isEqualTo(GroupPullExecutionStatus.EXECUTING.code());
        assertThat(expectedStage.get()).isEqualTo(GroupPullExecutionStage.ADD_MATERIALS.code());
        assertThat(terminalStatus.get()).isEqualTo(GroupPullExecutionStatus.FAILED.code());
        assertThat(terminalStage.get()).isEqualTo(GroupPullExecutionStage.ADD_MATERIALS.code());
    }

    @Test
    void terminalUpdateConflictSkipsAllSettlementSideEffects() {
        List<String> calls = new ArrayList<>();
        GroupPullMarketingMapper mapper = mapper((method, args) -> switch (method) {
            case "selectExecutionById" -> execution();
            case "selectTaskById" -> task();
            case "markExecutionTerminal" -> 0;
            case "completeFailedJoinedMaterials", "releaseUnjoinedMaterials",
                    "cancelMarketingQuota", "moveBuilderAccount", "markExecutionReleased" -> {
                calls.add(method);
                yield 1;
            }
            default -> throw new UnsupportedOperationException(method);
        });
        GroupPullMarketingFinalizer finalizer = new GroupPullMarketingFinalizer(
                mapper, null, new ReleasingOccupancyService(), null);

        finalizer.fail(501L, "测试失败");

        assertThat(calls).isEmpty();
    }

    private static GroupPullMarketingExecution execution() {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(501L);
        execution.setTaskId(101L);
        execution.setBuilderAccountId(201L);
        execution.setMarketingAccountId(301L);
        execution.setExecutionStatus(GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(GroupPullExecutionStage.ADD_MATERIALS.code());
        return execution;
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMaterialPerGroup(3);
        return task;
    }

    private static GroupPullMarketingMapper mapper(Invocation invocation) {
        Object proxy = Proxy.newProxyInstance(
                GroupPullMarketingMapper.class.getClassLoader(),
                new Class<?>[]{GroupPullMarketingMapper.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return "GroupPullMarketingMapperTestProxy";
                    }
                    return invocation.invoke(method.getName(), args);
                });
        return GroupPullMarketingMapper.class.cast(proxy);
    }

    /** 始终成功释放测试账号占用。 */
    private static final class ReleasingOccupancyService extends MarketingAccountOccupancyService {

        private ReleasingOccupancyService() {
            super(null, null);
        }

        @Override
        public boolean releaseTaskAccount(Long taskId, Long accountId) {
            return true;
        }
    }

    @FunctionalInterface
    private interface Invocation {

        Object invoke(String method, Object[] args);
    }
}
