package com.armada.task.worker;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

    private static final String STATE_SOURCE_JOIN_TASK_STATUS = "JOIN_TASK_STATUS";
    private static final String STATE_SOURCE_JOIN_TASK_STATUS_NOT_FOUND = "JOIN_TASK_STATUS_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(JoinTaskWorker.class);

    private final JoinTaskMapper joinTaskMapper;
    private final JoinTaskResultMapper resultMapper;
    private final AccountMapper accountMapper;
    private final AccountStateMapper accountStateMapper;
    private final GroupJoinPort groupJoinPort;
    private final AccountRuntimeStatusPort accountRuntimeStatusPort;
    private final Executor executor;
    private final Executor laneExecutor;
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
            AccountRuntimeStatusPort accountRuntimeStatusPort,
            @Value("${armada.join-task.worker.pool-size:4}") int poolSize,
            @Value("${armada.join-task.worker.account-lane-pool-size:16}") int lanePoolSize) {
        this(joinTaskMapper, resultMapper, accountMapper, accountStateMapper, groupJoinPort,
                accountRuntimeStatusPort,
                newPool(poolSize, "join-task-worker-"),
                newPool(lanePoolSize, "join-task-account-"),
                Thread::sleep);
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
            AccountRuntimeStatusPort accountRuntimeStatusPort,
            Executor executor,
            Executor laneExecutor,
            Sleeper sleeper) {
        this.joinTaskMapper = joinTaskMapper;
        this.resultMapper = resultMapper;
        this.accountMapper = accountMapper;
        this.accountStateMapper = accountStateMapper;
        this.groupJoinPort = groupJoinPort;
        this.accountRuntimeStatusPort = accountRuntimeStatusPort;
        this.executor = executor;
        this.laneExecutor = laneExecutor;
        this.sleeper = sleeper;
    }

    /**
     * 创建固定大小的守护线程池，并为线程设置便于排障的递增名称。
     *
     * @param poolSize         线程数；非正数按 1 处理
     * @param threadNamePrefix 线程名前缀
     * @return 新建的固定线程池
     */
    private static ExecutorService newPool(int poolSize, String threadNamePrefix) {
        int size = poolSize > 0 ? poolSize : 1;
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(size, task -> {
            Thread thread = new Thread(task, threadNamePrefix + seq.incrementAndGet());
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
     * 在指定租户上下文中尝试把任务标记为失败，并在结束后恢复调用线程原有上下文。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务 ID
     * @param stage    失败发生阶段，用于日志定位
     * @param cause    原始异常
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
     * 尽力把任务标记为 FAILED；状态更新本身失败时仅记录日志，避免覆盖原始异常。
     *
     * @param taskId 任务 ID
     * @param stage  失败发生阶段
     * @param cause  原始异常
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
     * 执行一轮任务：校验任务状态、读取待执行明细、运行账号通道，并在无待执行项时收敛为 DONE。
     *
     * @param taskId 任务 ID
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
        runAccountLanes(task, rows);
        if (resultMapper.selectPendingResultsByTask(taskId).isEmpty()) {
            joinTaskMapper.updateTaskStatus(taskId, JoinTaskStatus.DONE, System.currentTimeMillis());
        }
        log.info("进群任务 worker 完成一轮 taskId={} processed={}", taskId, rows.size());
    }

    /**
     * 按账号 ID 对待执行明细分组；不同账号通道并发执行，同一账号的明细由单个通道串行执行。
     * 当前轮次会等待所有账号通道结束后再返回。
     *
     * @param task 任务配置
     * @param rows 本轮待执行明细
     */
    private void runAccountLanes(JoinTask task, List<JoinTaskResult> rows) {
        Map<Long, List<JoinTaskResult>> lanes = new LinkedHashMap<>();
        for (JoinTaskResult row : rows) {
            lanes.computeIfAbsent(row.getAccountId(), ignored -> new java.util.ArrayList<>()).add(row);
        }
        Long tenantId = TenantContext.get();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>(lanes.size());
        for (List<JoinTaskResult> lane : lanes.values()) {
            futures.add(CompletableFuture.runAsync(
                    () -> runLaneWithTenant(tenantId, task, lane),
                    laneExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    /**
     * 在租户上下文中串行执行一个账号通道，并仅在该账号相邻明细之间等待任务配置的间隔。
     * 方法结束后恢复账号通道线程原有租户上下文。
     *
     * @param tenantId 当前任务租户 ID
     * @param task     任务配置
     * @param lane     同一账号的有序待执行明细
     */
    private void runLaneWithTenant(Long tenantId, JoinTask task, List<JoinTaskResult> lane) {
        Long previousTenant = TenantContext.get();
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
        try {
            for (int i = 0; i < lane.size(); i++) {
                processRow(task, lane.get(i));
                if (i < lane.size() - 1) {
                    sleepQuietly(nextIntervalMs(task));
                }
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 处理单条进群明细：解析账号、校验协议在线状态、执行进群及重试，并在结束时刷新任务计数。
     *
     * @param task 任务配置
     * @param row  待执行明细
     */
    private void processRow(JoinTask task, JoinTaskResult row) {
        try {
            Account account = resolveAccount(row);
            if (account == null) {
                fail(row, REASON_ACCOUNT_NOT_FOUND);
                return;
            }
            ProtocolAccountRef ref = protocolAccount(account);
            if (!isProtocolOnline(account, ref)) {
                fail(row, REASON_ACCOUNT_NOT_ONLINE);
                return;
            }
            joinWithRetries(task, row, ref);
        } catch (RuntimeException ex) {
            fail(row, reason(ex));
        } finally {
            joinTaskMapper.refreshCounters(task.getId());
        }
    }

    /**
     * 使用同一账号对同一群链接执行进群；{@code retryLimit} 表示首次调用之外允许的额外重试次数。
     * 永久失败立即结束，可重试失败按当前账号间隔等待后继续，其他账号通道不受影响。
     *
     * @param task 任务配置
     * @param row 待执行明细
     * @param ref 统一协议账号引用
     */
    private void joinWithRetries(JoinTask task, JoinTaskResult row, ProtocolAccountRef ref) {
        int retryLimit = task.isRetryEnabled() ? Math.max(0, task.getRetryLimit()) : 0;
        GroupJoinCommand command = new GroupJoinCommand(
                ref,
                row.getLink(),
                "join-task-result:" + row.getId());
        for (int retryIndex = 0; ; retryIndex++) {
            try {
                GroupJoinResult result = groupJoinPort.join(command);
                if (result != null && result.joined()) {
                    resultMapper.updateResultSuccess(row.getId(), nullToEmpty(result.groupJid()),
                            System.currentTimeMillis());
                    return;
                }
                fail(row, REASON_JOIN_PENDING_APPROVAL);
                return;
            } catch (RuntimeException ex) {
                if (retryIndex >= retryLimit || !isRetryable(ex)) {
                    fail(row, reason(ex));
                    return;
                }
                sleepQuietly(nextIntervalMs(task));
            }
        }
    }

    /**
     * 判断进群异常是否允许重试：优先采用协议层显式标记，旧协议响应再按永久错误码兜底。
     * 非协议异常按临时异常处理。
     *
     * @param ex 进群调用异常
     * @return {@code true} 表示可重试
     */
    private static boolean isRetryable(RuntimeException ex) {
        if (!(ex instanceof ProtocolException protocolException)) {
            return true;
        }
        if (protocolException.retryable().isPresent()) {
            return protocolException.retryable().orElse(false);
        }
        return switch (protocolException.errorCode()) {
            case INVITE_INVALID,
                    INVITE_REVOKED,
                    GROUP_UNAVAILABLE,
                    ACCOUNT_REACHOUT_RESTRICTED,
                    ACCOUNT_NOT_FOUND,
                    ACCOUNT_NOT_ONLINE,
                    BAD_REQUEST,
                    INVALID_GROUP_LINK,
                    GROUP_JOIN_REJECTED,
                    ANDROID_RESPONSE_UNRECOGNIZED,
                    UNSUPPORTED_BACKEND,
                    NEED_REAUTH,
                    PROXY_REQUIRED -> false;
            default -> true;
        };
    }

    /**
     * 查询本行绑定的有效账号，并校验统一协议引用需要的账号标识存在。
     *
     * @param row 任务明细
     * @return 可用账号；账号不存在、协议账号标识或手机号为空时返回 {@code null}
     */
    private Account resolveAccount(JoinTaskResult row) {
        if (row.getAccountId() == null) {
            return null;
        }
        Account account = accountMapper.selectActiveById(row.getAccountId());
        if (account == null
                || account.getProtocolAccountId() == null
                || account.getProtocolAccountId().isBlank()
                || account.getWsPhone() == null
                || account.getWsPhone().isBlank()) {
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
     * @param ref 统一协议账号引用
     * @return 协议状态为 ONLINE 时返回 true
     */
    private boolean isProtocolOnline(Account account, ProtocolAccountRef ref) {
        try {
            ProtocolAccountRuntimeStatus status = accountRuntimeStatusPort.status(ref);
            if (status != null && status.online()) {
                return true;
            }
            markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS);
            log.warn("进群任务账号协议状态非 ONLINE accountId={} backend={} protocolAccountId={} protocolState={}",
                    account.getId(), ref.backend(), ref.protocolAccountId(),
                    status == null ? null : status.state());
            return false;
        } catch (ProtocolException ex) {
            if (ex.errorCode() != ProtocolErrorCode.ACCOUNT_NOT_FOUND
                    && ex.errorCode() != ProtocolErrorCode.ACCOUNT_NOT_ONLINE) {
                throw ex;
            }
            markAccountOffline(account, STATE_SOURCE_JOIN_TASK_STATUS_NOT_FOUND);
            log.warn("进群任务账号协议状态不可用 accountId={} backend={} protocolAccountId={} code={}",
                    account.getId(), ref.backend(), ref.protocolAccountId(), ex.errorCode());
            return false;
        }
    }

    /**
     * 把账号本地登录态同步为离线，并记录此次状态同步来源。
     *
     * @param account     待更新账号
     * @param stateSource 状态来源标识
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
     * 将任务明细标记为失败，并对入库失败原因做空值和长度保护。
     *
     * @param row    任务明细
     * @param reason 失败原因或协议错误码
     */
    private void fail(JoinTaskResult row, String reason) {
        resultMapper.updateResultFailed(row.getId(), safeReason(reason), System.currentTimeMillis());
    }

    /**
     * 提取适合写入任务明细的失败原因；协议异常优先保留稳定的协议错误码。
     *
     * @param ex 执行异常
     * @return 失败原因
     */
    private static String reason(RuntimeException ex) {
        if (ex instanceof ProtocolException protocolException) {
            return protocolException.errorCode().name();
        }
        return ex.getMessage();
    }

    /**
     * 根据任务分配方式选择对应的间隔配置，并在闭区间内随机生成下一次等待毫秒数。
     *
     * @param task 任务配置
     * @return 等待毫秒数；配置为 0 时返回 0
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
     * 执行可替换的等待逻辑；线程被中断时恢复中断标记，不向外抛出中断异常。
     *
     * @param millis 等待毫秒数；非正数直接返回
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
     * 将可空字符串转换为空字符串，避免成功结果写入 {@code null}。
     *
     * @param value 原始字符串
     * @return 原值或空字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 将失败原因规范为可入库值：空原因使用 UNKNOWN，超长原因截断为 255 个字符。
     *
     * @param reason 原始失败原因
     * @return 安全的入库失败原因
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
        shutdown(executor);
        if (laneExecutor != executor) {
            shutdown(laneExecutor);
        }
    }

    /**
     * 当执行器支持生命周期管理时立即发起关闭；外部传入的普通 {@link Executor} 不做处理。
     *
     * @param target 待关闭执行器
     */
    private static void shutdown(Executor target) {
        if (target instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }
}
