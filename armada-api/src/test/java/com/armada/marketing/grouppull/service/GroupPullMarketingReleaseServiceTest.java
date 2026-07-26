package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingGroupOccupancyService;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拉群营销安全释放状态边界测试。 */
class GroupPullMarketingReleaseServiceTest {

    @Test
    void releaseWaitsForFormalGroupExecutionToFinish() {
        ReleaseFixture fixture = new ReleaseFixture();
        fixture.activeFormalExecutions = 1;
        fixture.cancelableExecutions = List.of(execution(501L, 601L, 701L));

        assertThat(fixture.service().tryRelease(101L)).isFalse();

        assertThat(fixture.calls)
                .containsSubsequence("cancelPreGroupExecution", "releaseExecutionMaterials",
                        "cancelMarketingQuota", "releaseTaskAccount", "markExecutionReleased")
                .doesNotContain("cancelPendingMarketingTaskCommands", "releaseResidualAccounts",
                        "releaseGroup", "markResourceReleased");
    }

    @Test
    void releaseCancelsOnlyPendingCommandsThenWaitsForInFlightAttempt() {
        ReleaseFixture fixture = new ReleaseFixture();
        fixture.unfinishedAttempts = 1;

        assertThat(fixture.service().tryRelease(101L)).isFalse();

        assertThat(fixture.calls)
                .containsSubsequence("cancelPendingMarketingTaskCommands:11:101",
                        "markCanceledAttempts:11:101", "markDeadAttempts:11:101")
                .doesNotContain("releaseResidualAccounts", "releaseGroup", "markResourceReleased");
    }

    @Test
    void releaseMarksResourceReleasedAfterAccountsAndOwnedGroupAreReleased() {
        ReleaseFixture fixture = new ReleaseFixture();

        assertThat(fixture.service().tryRelease(101L)).isTrue();

        assertThat(fixture.calls)
                .containsSubsequence("releaseResidualAccounts", "markTaskExecutionsReleased",
                        "releaseGroup", "markResourceReleased");
    }

    @Test
    void releaseKeepsReleasingStateWhenGroupLockOwnershipDoesNotMatch() {
        ReleaseFixture fixture = new ReleaseFixture();
        fixture.groupReleased = false;
        fixture.groupFree = false;

        assertThat(fixture.service().tryRelease(101L)).isFalse();

        assertThat(fixture.calls).contains("releaseGroup", "isGroupFree")
                .doesNotContain("markResourceReleased");
    }

    @Test
    void releaseDefersWhenCancelableExecutionWinsRace() {
        ReleaseFixture fixture = new ReleaseFixture();
        fixture.cancelableExecutions = List.of(execution(501L, 601L, 701L));
        fixture.cancelPreGroupExecutionResult = 0;

        assertThat(fixture.service().tryRelease(101L)).isFalse();

        assertThat(fixture.calls)
                .contains("cancelPreGroupExecution")
                .doesNotContain("releaseExecutionMaterials", "cancelMarketingQuota",
                        "cancelPendingMarketingTaskCommands:11:101", "releaseResidualAccounts",
                        "releaseGroup", "markResourceReleased");
    }

    private static GroupPullMarketingExecution execution(Long id, Long builderId, Long marketerId) {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(id);
        execution.setBuilderAccountId(builderId);
        execution.setMarketingAccountId(marketerId);
        return execution;
    }

    /** 使用轻量代理记录释放副作用，避免测试依赖外部数据库或 mock agent。 */
    private static final class ReleaseFixture {

        private final List<String> calls = new ArrayList<>();
        private List<GroupPullMarketingExecution> cancelableExecutions = List.of();
        private long activeFormalExecutions;
        private long unfinishedAttempts;
        private boolean groupReleased = true;
        private boolean groupFree;
        private int cancelPreGroupExecutionResult = 1;

        private GroupPullMarketingReleaseService service() {
            return new GroupPullMarketingReleaseService(
                    groupPullMapper(),
                    marketingTaskMapper(),
                    outboxMapper(),
                    new RecordingAccountOccupancyService(calls),
                    new RecordingGroupOccupancyService(calls, this));
        }

        private GroupPullMarketingMapper groupPullMapper() {
            return proxy(GroupPullMarketingMapper.class, (method, args) -> switch (method) {
                case "selectTaskForUpdate" -> marketingTask();
                case "selectTaskById" -> pullTask();
                case "selectCancelableExecutions" -> cancelableExecutions;
                case "countActiveFormalExecutions" -> activeFormalExecutions;
                case "cancelPreGroupExecution" -> {
                    calls.add(method);
                    yield cancelPreGroupExecutionResult;
                }
                case "releaseExecutionMaterials", "cancelMarketingQuota",
                        "markExecutionReleased", "markTaskExecutionsReleased", "markResourceReleased" -> {
                    calls.add(method);
                    yield 1;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private MarketingTaskMapper marketingTaskMapper() {
            return proxy(MarketingTaskMapper.class, (method, args) -> switch (method) {
                case "markCanceledOutboxAttemptsSkipped" -> {
                    calls.add("markCanceledAttempts:" + args[0] + ":" + args[1]);
                    yield 1;
                }
                case "markDeadOutboxAttemptsFailed" -> {
                    calls.add("markDeadAttempts:" + args[0] + ":" + args[1]);
                    yield 1;
                }
                case "countUnfinishedAttempts" -> unfinishedAttempts;
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private ProtocolCommandOutboxMapper outboxMapper() {
            return proxy(ProtocolCommandOutboxMapper.class, (method, args) -> {
                if (!"cancelPendingMarketingTaskCommands".equals(method)) {
                    throw new UnsupportedOperationException(method);
                }
                calls.add("cancelPendingMarketingTaskCommands:" + args[0] + ":" + args[1]);
                return 1;
            });
        }

        private MarketingTask marketingTask() {
            MarketingTask task = new MarketingTask();
            task.setId(101L);
            task.setTenantId(11L);
            task.setAccountGroupId(201L);
            return task;
        }

        private GroupPullMarketingTask pullTask() {
            GroupPullMarketingTask task = new GroupPullMarketingTask();
            task.setMarketingTaskId(101L);
            task.setTenantId(11L);
            task.setResourceStatus(GroupPullResourceStatus.RELEASING.code());
            return task;
        }
    }

    /** 记录账号释放动作。 */
    private static final class RecordingAccountOccupancyService
            extends MarketingAccountOccupancyService {

        private final List<String> calls;

        private RecordingAccountOccupancyService(List<String> calls) {
            super(null, null);
            this.calls = calls;
        }

        @Override
        public boolean releaseTaskAccount(Long taskId, Long accountId) {
            calls.add("releaseTaskAccount");
            return true;
        }

        @Override
        public int releaseGroupPullResidualAccounts(Long taskId) {
            calls.add("releaseResidualAccounts");
            return 1;
        }
    }

    /** 记录营销分组释放与空闲检查动作。 */
    private static final class RecordingGroupOccupancyService extends MarketingGroupOccupancyService {

        private final List<String> calls;
        private final ReleaseFixture fixture;

        private RecordingGroupOccupancyService(List<String> calls, ReleaseFixture fixture) {
            super(null);
            this.calls = calls;
            this.fixture = fixture;
        }

        @Override
        public boolean release(Long groupId,
                               com.armada.marketing.model.enums.MarketingBusinessType businessType,
                               Long taskId,
                               long now) {
            calls.add("releaseGroup");
            return fixture.groupReleased;
        }

        @Override
        public boolean isFree(Long groupId) {
            calls.add("isGroupFree");
            return fixture.groupFree;
        }
    }

    @FunctionalInterface
    private interface ProxyInvocation {

        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, ProxyInvocation invocation) {
        Object proxy = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? type.getSimpleName() + "TestProxy" : null;
                    }
                    return invocation.invoke(method.getName(), args);
                });
        return type.cast(proxy);
    }
}
