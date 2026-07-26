package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.port.GroupCreatePort;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 拉群营销单群执行的事务锁顺序测试。 */
class GroupPullMarketingExecutionWorkerTest {

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    // 测试只验证同一事务回调内的 Mapper 调用顺序。
                }

                @Override
                public void rollback(TransactionStatus status) {
                    // 测试只验证同一事务回调内的 Mapper 调用顺序。
                }
            };

    @Test
    void groupNameFreezingLocksTaskBeforeReadingExecutionAgain() {
        List<String> calls = new ArrayList<>();
        GroupPullMarketingExecution execution = execution();
        GroupPullMarketingMapper mapper = mapper(calls, execution);
        GroupCreatePort failingCreatePort = command -> {
            throw new ProtocolException(
                    ProtocolErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE,
                    "stop after group name");
        };
        GroupPullMarketingExecutionWorker worker = new GroupPullMarketingExecutionWorker(
                mapper,
                null,
                null,
                null,
                failingCreatePort,
                null,
                null,
                null,
                null,
                null,
                NO_OP_TRANSACTION_MANAGER);

        worker.process(execution.getId());

        assertThat(calls).containsSubsequence(
                "selectTaskForUpdate",
                "selectExecutionById",
                "saveGroupNameIfAbsent");
    }

    private static GroupPullMarketingMapper mapper(
            List<String> calls,
            GroupPullMarketingExecution execution) {
        Object proxy = Proxy.newProxyInstance(
                GroupPullMarketingMapper.class.getClassLoader(),
                new Class<?>[]{GroupPullMarketingMapper.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return "GroupPullMarketingExecutionWorkerTestMapper";
                    }
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "selectExecutionById" -> execution;
                        case "tryLeaseExecution", "saveGroupNameIfAbsent", "updateBlockReason",
                                "delayExecution" -> 1;
                        case "selectAccountRef" -> account((Long) args[0]);
                        case "selectTaskById" -> task();
                        case "selectTaskForUpdate" -> new MarketingTask();
                        case "countNamedExecutions" -> 0L;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        return GroupPullMarketingMapper.class.cast(proxy);
    }

    private static GroupPullMarketingExecution execution() {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(501L);
        execution.setTaskId(101L);
        execution.setBuilderAccountId(201L);
        execution.setMarketingAccountId(301L);
        execution.setExecutionStatus(GroupPullExecutionStatus.PREPARING.code());
        execution.setCurrentStage(GroupPullExecutionStage.CREATE_GROUP.code());
        execution.setNextExecuteAt(0L);
        return execution;
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(101L);
        task.setGroupNamePrefix("测试群");
        return task;
    }

    private static GroupPullAccountRefRow account(Long accountId) {
        GroupPullAccountRefRow account = new GroupPullAccountRefRow();
        account.setAccountId(accountId);
        account.setWsPhone("861380000" + accountId);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc_" + accountId);
        account.setAccountState(AccountStateCode.NORMAL);
        account.setLoginState(AccountLoginStateCode.ONLINE);
        return account;
    }
}
