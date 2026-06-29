package com.armada.task.worker;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进群任务后台执行器。
 *
 * <p>它不是定时任务。用户启动任务后,service 将任务置 RUNNING 并提交本 worker 到线程池;
 * worker 在租户上下文内逐行调用协议层 join,按任务间隔 sleep,最后收敛 DONE。</p>
 */
@Component
public class JoinTaskWorker implements DisposableBean {

    public static final String REASON_JOIN_PENDING_APPROVAL = "JOIN_PENDING_APPROVAL";
    public static final String REASON_ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(JoinTaskWorker.class);

    private final JoinTaskMapper joinTaskMapper;
    private final JoinTaskResultMapper resultMapper;
    private final AccountMapper accountMapper;
    private final GroupJoinPort groupJoinPort;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public JoinTaskWorker(
            JoinTaskMapper joinTaskMapper,
            JoinTaskResultMapper resultMapper,
            AccountMapper accountMapper,
            GroupJoinPort groupJoinPort,
            @Value("${armada.join-task.worker.pool-size:4}") int poolSize) {
        this(joinTaskMapper, resultMapper, accountMapper, groupJoinPort, newPool(poolSize), Thread::sleep);
    }

    public JoinTaskWorker(
            JoinTaskMapper joinTaskMapper,
            JoinTaskResultMapper resultMapper,
            AccountMapper accountMapper,
            GroupJoinPort groupJoinPort,
            Executor executor,
            Sleeper sleeper) {
        this.joinTaskMapper = joinTaskMapper;
        this.resultMapper = resultMapper;
        this.accountMapper = accountMapper;
        this.groupJoinPort = groupJoinPort;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    private static ExecutorService newPool(int poolSize) {
        int size = poolSize > 0 ? poolSize : 1;
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(size, task -> {
            Thread thread = new Thread(task, "join-task-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void startAsync(Long tenantId, Long taskId) {
        if (tenantId == null || taskId == null) {
            return;
        }
        String key = tenantId + ":" + taskId;
        if (!activeTasks.add(key)) {
            log.debug("进群任务 worker 已在运行 tenantId={} taskId={}", tenantId, taskId);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runTask(tenantId, taskId);
                } finally {
                    activeTasks.remove(key);
                }
            });
        } catch (RuntimeException ex) {
            activeTasks.remove(key);
            log.warn("进群任务 worker 提交失败 tenantId={} taskId={} msg={}", tenantId, taskId, ex.getMessage());
        }
    }

    public void runTask(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            doRunTask(taskId);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void doRunTask(Long taskId) {
        JoinTask task = joinTaskMapper.selectByTenantAndId(taskId);
        if (task == null) {
            log.warn("进群任务 worker 退出:任务不存在 taskId={}", taskId);
            return;
        }
        if (!JoinTaskStatus.RUNNING.equals(task.getStatus())) {
            log.info("进群任务 worker 退出:任务非 RUNNING taskId={} status={}", taskId, task.getStatus());
            return;
        }
        List<JoinTaskResult> rows = resultMapper.selectPendingResultsByTask(taskId);
        if (rows.isEmpty()) {
            joinTaskMapper.updateTaskStatus(taskId, JoinTaskStatus.DONE, System.currentTimeMillis());
            return;
        }
        log.info("进群任务 worker 开始 taskId={} pending={}", taskId, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            processRow(task, rows.get(i));
            if (i < rows.size() - 1) {
                sleepQuietly(nextIntervalMs(task));
            }
        }
        if (resultMapper.selectPendingResultsByTask(taskId).isEmpty()) {
            joinTaskMapper.updateTaskStatus(taskId, JoinTaskStatus.DONE, System.currentTimeMillis());
        }
        log.info("进群任务 worker 完成一轮 taskId={} processed={}", taskId, rows.size());
    }

    private void processRow(JoinTask task, JoinTaskResult row) {
        try {
            Account account = resolveAccount(row);
            if (account == null) {
                fail(row, REASON_ACCOUNT_NOT_FOUND);
                return;
            }
            GroupJoinResult result = groupJoinPort.join(account.getProtocolAccountId(), row.getLink());
            if (result != null && result.joined()) {
                resultMapper.updateResultSuccess(row.getId(), nullToEmpty(result.groupJid()), System.currentTimeMillis());
                return;
            }
            fail(row, REASON_JOIN_PENDING_APPROVAL);
        } catch (RuntimeException ex) {
            fail(row, reason(ex));
        } finally {
            joinTaskMapper.refreshCounters(task.getId());
        }
    }

    private Account resolveAccount(JoinTaskResult row) {
        if (row.getAccountId() == null) {
            return null;
        }
        Account account = accountMapper.selectActiveById(row.getAccountId());
        if (account == null || account.getProtocolAccountId() == null || account.getProtocolAccountId().isBlank()) {
            return null;
        }
        return account;
    }

    private void fail(JoinTaskResult row, String reason) {
        resultMapper.updateResultFailed(row.getId(), safeReason(reason), System.currentTimeMillis());
    }

    private static String reason(RuntimeException ex) {
        if (ex instanceof ProtocolException protocolException) {
            return protocolException.protocolCode()
                    .orElse(protocolException.errorCode().name());
        }
        return ex.getMessage();
    }

    private long nextIntervalMs(JoinTask task) {
        int minSec;
        int maxSec;
        if (DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(task.getDistributionMode())) {
            minSec = task.getMultiIntervalMinSec();
            maxSec = task.getMultiIntervalMaxSec();
        } else {
            minSec = task.getFixedIntervalMinSec();
            maxSec = task.getFixedIntervalMaxSec();
        }
        int lo = Math.max(0, minSec);
        int hi = Math.max(lo, maxSec);
        if (hi == 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong((long) lo * 1000L, (long) hi * 1000L + 1L);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    @Override
    public void destroy() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }
}
