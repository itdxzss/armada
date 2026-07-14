package com.armada.task.worker;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.ProtocolAccountStatus;
import com.armada.platform.protocol.port.AccountLifecyclePort;
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
    public static final String REASON_ACCOUNT_NOT_ONLINE = "ACCOUNT_NOT_ONLINE";

    private static final String PROTOCOL_STATE_ONLINE = "ONLINE";
    private static final String PROTOCOL_CODE_ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    private static final String STATE_SOURCE_JOIN_TASK_STATUS = "JOIN_TASK_STATUS";
    private static final String STATE_SOURCE_JOIN_TASK_STATUS_NOT_FOUND = "JOIN_TASK_STATUS_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(JoinTaskWorker.class);

    private final JoinTaskMapper joinTaskMapper;
    private final JoinTaskResultMapper resultMapper;
    private final AccountMapper accountMapper;
    private final AccountStateMapper accountStateMapper;
    private final GroupJoinPort groupJoinPort;
    private final AccountLifecyclePort accountLifecyclePort;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();

    /**
     * 可替换的休眠动作，生产环境使用 {@link Thread#sleep(long)}，测试中注入空实现以避免等待。
     */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * 休眠指定时间。
         *
         * @param millis 休眠毫秒数
         * @throws InterruptedException 当前线程被中断时抛出
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Spring 生产构造器，根据配置创建进群任务专用线程池。
     */
    @Autowired
    public JoinTaskWorker(
            JoinTaskMapper joinTaskMapper,
            JoinTaskResultMapper resultMapper,
            AccountMapper accountMapper,
            AccountStateMapper accountStateMapper,
            GroupJoinPort groupJoinPort,
            AccountLifecyclePort accountLifecyclePort,
            @Value("${armada.join-task.worker.pool-size:4}") int poolSize) {
        this(joinTaskMapper, resultMapper, accountMapper, accountStateMapper, groupJoinPort,
                accountLifecyclePort, newPool(poolSize), Thread::sleep);
    }

    /**
     * 完整依赖构造器，允许测试替换执行器和休眠动作。
     */
    public JoinTaskWorker(
            JoinTaskMapper joinTaskMapper,
            JoinTaskResultMapper resultMapper,
            AccountMapper accountMapper,
            AccountStateMapper accountStateMapper,
            GroupJoinPort groupJoinPort,
            AccountLifecyclePort accountLifecyclePort,
            Executor executor,
            Sleeper sleeper) {
        this.joinTaskMapper = joinTaskMapper;
        this.resultMapper = resultMapper;
        this.accountMapper = accountMapper;
        this.accountStateMapper = accountStateMapper;
        this.groupJoinPort = groupJoinPort;
        this.accountLifecyclePort = accountLifecyclePort;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    /**
     * 创建固定大小的守护线程池。
     *
     * @param poolSize 配置的线程数；非正数按 1 处理
     * @return 进群任务专用线程池
     */
    private static ExecutorService newPool(int poolSize) {
        int size = poolSize > 0 ? poolSize : 1;
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(size, task -> {
            Thread thread = new Thread(task, "join-task-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 异步提交一个租户下的进群任务。
     *
     * <p>同一 {@code tenantId + taskId} 同时只允许执行一次；线程池拒绝任务时尽力把任务标记为
     * FAILED，并释放运行中标记。</p>
     *
     * @param tenantId 租户 ID
     * @param taskId 进群任务 ID
     */
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
            markTaskFailedQuietly(tenantId, taskId, "提交失败", ex);
        }
    }

    /**
     * 在指定租户上下文中同步执行一轮任务。
     *
     * <p>该方法负责保存并恢复调用线程原有租户上下文；未捕获异常会转成任务 FAILED，避免异常
     * 泄漏到线程池。</p>
     *
     * @param tenantId 租户 ID
     * @param taskId 进群任务 ID
     */
    public void runTask(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            doRunTask(taskId);
        } catch (RuntimeException ex) {
            markTaskFailedQuietly(taskId, "执行异常", ex);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 切换到目标租户后，尽力把提交阶段失败的任务标记为 FAILED。
     */
    private void markTaskFailedQuietly(Long tenantId, Long taskId, String stage, RuntimeException cause) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            markTaskFailedQuietly(taskId, stage, cause);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 尽力把当前租户下的任务标记为 FAILED；状态更新失败只记日志，不覆盖原始异常。
     */
    private void markTaskFailedQuietly(Long taskId, String stage, RuntimeException cause) {
        try {
            joinTaskMapper.updateTaskStatus(taskId, JoinTaskStatus.FAILED, System.currentTimeMillis());
            log.warn("进群任务 worker {} taskId={} 已标记 FAILED msg={}", stage, taskId, cause.getMessage(), cause);
        } catch (RuntimeException updateEx) {
            log.warn("进群任务 worker {} taskId={} 且标记 FAILED 失败 msg={} updateMsg={}",
                    stage, taskId, cause.getMessage(), updateEx.getMessage(), updateEx);
        }
    }

    /**
     * 执行任务主流程：校验任务状态、读取待处理行、逐行进群，并在无待处理数据时收敛为 DONE。
     *
     * @param taskId 进群任务 ID
     */
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

    /**
     * 处理单条进群结果：解析账号、检查协议在线状态、调用统一进群端口并落成功或失败结果。
     *
     * <p>无论本行成功还是失败，最终都会刷新任务统计计数。</p>
     */
    private void processRow(JoinTask task, JoinTaskResult row) {
        try {
            Account account = resolveAccount(row);
            if (account == null) {
                fail(row, REASON_ACCOUNT_NOT_FOUND);
                return;
            }
            if (!isProtocolOnline(account)) {
                fail(row, REASON_ACCOUNT_NOT_ONLINE);
                return;
            }
            // 把账号的协议路由信息和本行任务标识一起交给统一进群端口；Worker 不再直接调用 Web 专用签名。
            GroupJoinResult result = groupJoinPort.join(new GroupJoinCommand(
                    protocolAccount(account),
                    row.getLink(),
                    "join-task-result:" + row.getId()));
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

    /**
     * 查询本行绑定的有效账号，并校验 Web 协议账号标识存在。
     *
     * @param row 待处理任务结果
     * @return 可用账号；账号不存在或协议账号标识为空时返回 null
     */
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

    /**
     * 把账号实体转换为协议层统一账号引用。
     *
     * <p>未知或空 protocolId 按存量规则回退为 Web；Web 和 Android 所需的两种账号标识
     * 都保留在引用中，由被选中的 backend 使用自己的字段。</p>
     */
    private static ProtocolAccountRef protocolAccount(Account account) {
        return new ProtocolAccountRef(
                account.getId(),
                ProtocolBackend.fromProtocolId(account.getProtocolId()),
                account.getProtocolAccountId(),
                account.getWsPhone());
    }

    /**
     * 查询账号当前协议状态。
     *
     * <p>明确离线或协议侧找不到账号时同步本地 OFFLINE；网络等非账号不存在异常继续向上抛出，
     * 避免把临时调用失败误写成账号离线。</p>
     *
     * @param account Armada 账号
     * @return 协议状态为 ONLINE 时返回 true
     */
    private boolean isProtocolOnline(Account account) {
        try {
            ProtocolAccountStatus status = accountLifecyclePort.status(account.getProtocolAccountId());
            if (status != null && PROTOCOL_STATE_ONLINE.equalsIgnoreCase(status.state())) {
                return true;
            }
            markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS);
            log.warn("进群任务账号协议状态非 ONLINE accountId={} protocolAccountId={} protocolState={}",
                    account.getId(), account.getProtocolAccountId(), status == null ? null : status.state());
            return false;
        } catch (ProtocolException ex) {
            if (!isProtocolAccountNotFound(ex)) {
                throw ex;
            }
            markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS_NOT_FOUND);
            log.warn("进群任务账号协议状态不存在 accountId={} protocolAccountId={} httpStatus={} protocolCode={}",
                    account.getId(), account.getProtocolAccountId(), ex.httpStatus(),
                    ex.protocolCode().orElse(null));
            return false;
        }
    }

    /**
     * 把账号登录状态更新为 OFFLINE，并记录本次状态收敛来源。
     */
    private void markAccountOffline(Account account, String stateSource) {
        AccountState row = new AccountState();
        row.setAccountId(account.getId());
        row.setLoginState(AccountLoginStateCode.OFFLINE);
        row.setStateSource(stateSource);
        long now = System.currentTimeMillis();
        row.setLastStateSyncTime(now);
        row.setUpdatedAt(now);
        accountStateMapper.updateLoginState(row);
    }

    /**
     * 判断协议异常是否表达账号不存在，兼容 HTTP 404 和协议原始错误码两种返回方式。
     */
    private static boolean isProtocolAccountNotFound(ProtocolException ex) {
        return ex.httpStatus() == 404
                || ex.protocolCode()
                .map(PROTOCOL_CODE_ACCOUNT_NOT_FOUND::equalsIgnoreCase)
                .orElse(false);
    }

    /**
     * 持久化单行失败结果，并统一限制原因文本。
     */
    private void fail(JoinTaskResult row, String reason) {
        resultMapper.updateResultFailed(row.getId(), safeReason(reason), System.currentTimeMillis());
    }

    /**
     * 从运行时异常中提取可持久化原因；协议异常优先保留协议原始错误码。
     */
    private static String reason(RuntimeException ex) {
        if (ex instanceof ProtocolException protocolException) {
            return protocolException.protocolCode()
                    .orElse(protocolException.errorCode().name());
        }
        return ex.getMessage();
    }

    /**
     * 根据任务分配模式计算下一行执行前的随机间隔。
     *
     * @return 闭区间内随机毫秒数；最大间隔为 0 时返回 0
     */
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

    /**
     * 执行可中断休眠；收到中断时恢复线程中断标记，不继续抛出。
     */
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

    /**
     * 把可空字符串转换为空字符串，避免成功结果写入 null 群 JID。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 标准化失败原因，空值兜底 UNKNOWN，超长文本截断为数据库字段允许的 255 个字符。
     */
    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    /**
     * Spring 销毁 Bean 时停止本类自行创建的线程池；测试注入的普通 Executor 无需处理。
     */
    @Override
    public void destroy() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }
}
