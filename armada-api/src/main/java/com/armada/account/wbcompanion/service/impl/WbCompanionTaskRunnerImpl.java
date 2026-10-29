package com.armada.account.wbcompanion.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.wbcompanion.mapper.WbCompanionTaskMapper;
import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.armada.account.wbcompanion.model.enums.WbCompanionStep;
import com.armada.account.wbcompanion.model.enums.WbCompanionTaskStatus;
import com.armada.account.wbcompanion.service.WbCompanionBindingService;
import com.armada.account.wbcompanion.service.WbCompanionTaskRunner;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.CompanionPairCodeResult;
import com.armada.platform.protocol.port.CompanionPairCodePort;
import com.armada.platform.wbpro.WbproIntegrationClient;
import com.armada.platform.wbpro.WbproIntegrationException;
import com.armada.platform.wbpro.WbproProperties;
import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每条连接三步：WB 用新配对码发起配对 → 安卓主设备输入同一个码 → 等 WB 确认上线；成功后做下一条，任何一条失败整批停下。
 *
 * <p>必须串行：安卓层在 120 秒内只认唯一一个未开始的配对请求，同时有两个会返回 AMBIGUOUS；WB 也要先有主设备才能挂影子。
 * 每次推进先用 next_run_at 做租约（条件更新），多个 Armada 实例只会有一个推进成功；网络调用不在数据库事务里。</p>
 */
@Service
public class WbCompanionTaskRunnerImpl implements WbCompanionTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(WbCompanionTaskRunnerImpl.class);
    /** 推进租约：要长于一次推进最长的网络耗时（WB 请求超时 30 秒），过期后其他实例可接手。 */
    private static final long LEASE_MILLIS = 60_000L;
    /** WB 发起后等关联设备的配对请求到达安卓主设备，再第一次输入配对码。 */
    private static final long FIRST_CODE_DELAY_MILLIS = 1_500L;
    /** 安卓返回 NO_PENDING（请求未到）时的重试窗口和间隔。 */
    private static final long NO_PENDING_WINDOW_MILLIS = 20_000L;
    private static final long RETRY_DELAY_MILLIS = 2_000L;
    /** 输入配对码后等 WB 上线的期限，与 WB 配对记录 180 秒期限一致。 */
    private static final long CONNECT_WINDOW_MILLIS = 180_000L;
    private static final long POLL_DELAY_MILLIS = 3_000L;
    /** WhatsApp 配对码字母表（不含 0、I、O、U），两端都按原样派生密钥，统一大写。 */
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTVWXYZ";
    private static final int CODE_LENGTH = 8;
    private static final String WB_CONNECTED = "CONNECTED";
    private static final String WB_FAILED = "FAILED";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WbCompanionTaskMapper taskMapper;
    private final AccountMapper accountMapper;
    private final WbproIntegrationClient client;
    private final WbproProperties properties;
    private final CompanionPairCodePort pairCodePort;
    private final WbCompanionBindingService bindingService;

    public WbCompanionTaskRunnerImpl(WbCompanionTaskMapper taskMapper, AccountMapper accountMapper,
                                     WbproIntegrationClient client, WbproProperties properties,
                                     CompanionPairCodePort pairCodePort, WbCompanionBindingService bindingService) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.client = client;
        this.properties = properties;
        this.pairCodePort = pairCodePort;
        this.bindingService = bindingService;
    }

    @Override
    public void advance(Long taskId) {
        WbCompanionTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() != WbCompanionTaskStatus.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        if (taskMapper.claim(taskId, task.getNextRunAt(), now + LEASE_MILLIS, now) != 1) {
            return;
        }
        Optional<WbproTarget> target = properties.target(task.getWbEnvId());
        if (target.isEmpty()) {
            finish(task, WbCompanionTaskStatus.FAILED, "WB_TARGET_REMOVED", null);
            return;
        }
        try {
            switch (task.getStep()) {
                case WbCompanionStep.WAITING -> start(task, target.get());
                case WbCompanionStep.WB_STARTED -> submitCode(task, target.get());
                default -> awaitConnected(task, target.get());
            }
        } catch (WbproIntegrationException ex) {
            finish(task, WbCompanionTaskStatus.FAILED, ex.getCode(), target.get());
        } catch (ProtocolException ex) {
            // 安卓层不可达时无法确认 primary_hello 是否已发出，不重提，整批停下。
            finish(task, WbCompanionTaskStatus.FAILED, "ANDROID_UNAVAILABLE", target.get());
        }
    }

    private void start(WbCompanionTask task, WbproTarget target) {
        if (task.getBaseCount() + task.getSucceededCount() >= task.getTargetCount()) {
            finish(task, WbCompanionTaskStatus.SUCCEEDED, null, target);
            return;
        }
        if (!online(task)) {
            finish(task, WbCompanionTaskStatus.FAILED, "ACCOUNT_OFFLINE", target);
            return;
        }
        String code = newCode();
        WbproModels.PairingStarted started = client.startPairing(target, new WbproIntegrationClient.WbproPairingCommand(
                task.getWbMemberId(), task.getWsPhone(), code, "armada-task-" + task.getId()));
        long now = System.currentTimeMillis();
        task.setStep(WbCompanionStep.WB_STARTED);
        task.setWbPairingId(started.pairingId());
        task.setWbRole(started.role());
        task.setPairCode(code);
        task.setStepDeadline(now + NO_PENDING_WINDOW_MILLIS);
        task.setNextRunAt(now + FIRST_CODE_DELAY_MILLIS);
        save(task, now);
    }

    private void submitCode(WbCompanionTask task, WbproTarget target) {
        CompanionPairCodeResult result = pairCodePort.submit(task.getWsPhone(), task.getPairCode());
        long now = System.currentTimeMillis();
        if (result.accepted()) {
            task.setStep(WbCompanionStep.CODE_SUBMITTED);
            task.setStepDeadline(now + CONNECT_WINDOW_MILLIS);
            task.setNextRunAt(now + POLL_DELAY_MILLIS);
            save(task, now);
        } else if (result.retryable() && now < task.getStepDeadline()) {
            task.setNextRunAt(now + RETRY_DELAY_MILLIS);
            save(task, now);
        } else {
            // AMBIGUOUS、SEND_FAILED、EXPIRED 等都不能重提同一个码；NO_PENDING 超出窗口也停下。
            finish(task, WbCompanionTaskStatus.FAILED, "ANDROID_" + result.reason(), target);
        }
    }

    private void awaitConnected(WbCompanionTask task, WbproTarget target) {
        WbproModels.PairingState state;
        try {
            state = client.pairing(target, task.getWbPairingId());
        } catch (WbproIntegrationException ex) {
            if (!WbproIntegrationException.PAIRING_NOT_FOUND.equals(ex.getCode())) {
                throw ex;
            }
            // WB 的配对记录只保留 180 秒；过期后用设备查询判断这一条是否已挂上。
            int expected = task.getBaseCount() + task.getSucceededCount() + 1;
            int actual = bindingService.sync(target, task.getAccountId(), task.getWsPhone())
                    .map(WbCompanionBindingService::connections).orElse(0);
            if (actual >= expected) {
                connected(task, target);
            } else {
                finish(task, WbCompanionTaskStatus.FAILED, "WB_PAIRING_EXPIRED", target);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (WB_CONNECTED.equals(state.status())) {
            connected(task, target);
        } else if (WB_FAILED.equals(state.status())) {
            finish(task, WbCompanionTaskStatus.FAILED,
                    state.errorCode() == null ? "WB_PAIRING_FAILED" : state.errorCode(), target);
        } else if (now > task.getStepDeadline()) {
            finish(task, WbCompanionTaskStatus.FAILED, "WB_TIMEOUT", target);
        } else {
            task.setNextRunAt(now + POLL_DELAY_MILLIS);
            save(task, now);
        }
    }

    private void connected(WbCompanionTask task, WbproTarget target) {
        task.setSucceededCount(task.getSucceededCount() + 1);
        clearStep(task);
        syncQuietly(task, target);
        if (task.getBaseCount() + task.getSucceededCount() >= task.getTargetCount()) {
            finish(task, WbCompanionTaskStatus.SUCCEEDED, null, null);
            return;
        }
        long now = System.currentTimeMillis();
        task.setNextRunAt(now);
        save(task, now);
    }

    /**
     * 结束任务并清掉配对码；target 不为空时顺带刷新绑定快照，让列表看到最新连接数。
     */
    private void finish(WbCompanionTask task, int status, String errorCode, WbproTarget target) {
        long now = System.currentTimeMillis();
        clearStep(task);
        task.setStatus(status);
        task.setErrorCode(errorCode);
        task.setFinishedAt(now);
        task.setNextRunAt(now);
        save(task, now);
        log.info("WB 分身任务结束 taskId={} accountId={} status={} succeeded={} errorCode={}",
                task.getId(), task.getAccountId(), status, task.getSucceededCount(), errorCode);
        if (target != null) {
            syncQuietly(task, target);
        }
    }

    private static void clearStep(WbCompanionTask task) {
        task.setStep(WbCompanionStep.WAITING);
        task.setWbPairingId(null);
        task.setWbRole(null);
        task.setPairCode(null);
        task.setStepDeadline(null);
    }

    private void save(WbCompanionTask task, long now) {
        task.setUpdatedAt(now);
        taskMapper.updateProgress(task);
    }

    private void syncQuietly(WbCompanionTask task, WbproTarget target) {
        try {
            bindingService.sync(target, task.getAccountId(), task.getWsPhone());
        } catch (WbproIntegrationException ex) {
            log.warn("WB 分身快照刷新失败 taskId={} accountId={} code={}", task.getId(), task.getAccountId(), ex.getCode());
        }
    }

    private boolean online(WbCompanionTask task) {
        List<Long> online = accountMapper.selectOnlineAccountIdsByIds(
                List.of(task.getAccountId()), AccountLoginStateCode.ONLINE);
        return online != null && online.contains(task.getAccountId());
    }

    /**
     * 生成 8 位配对码。
     *
     * @return 大写、来自 WhatsApp 配对码字母表的随机码
     */
    static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
