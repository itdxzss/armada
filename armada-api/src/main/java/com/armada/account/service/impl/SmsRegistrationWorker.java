package com.armada.account.service.impl;

import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.mapper.SmsRegistrationMapper;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.entity.SmsRegistration;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.enums.SmsRegistrationFailure;
import com.armada.account.model.enums.AccountTypeCode;
import com.armada.account.model.enums.SmsRegistrationState;
import com.armada.account.model.vo.SmsActivationVO;
import com.armada.account.service.SmsActivationService;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.platform.registration.go.GoRegistrationClient;
import com.armada.platform.registration.go.GoRegistrationRequest;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.armada.platform.registration.go.GoRegistrationResponse;
import com.armada.platform.registration.go.GoRegistrationSnapshot;
import com.armada.platform.registration.go.GoRegistrationState;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 接码订单 Go 注册的后台推进：每轮把所有“申请中/等码/验证中”的记录并发各推进一步。
 *
 * <p>不使用任何锁或租约，原因有二：部署只有一个后端进程，且上一轮全部做完才会开始下一轮，所以同一条记录
 * 不会被两处同时处理；重复调用 Go 的安全性来自稳定的 Idempotency-Key，而不是互斥。</p>
 *
 * <p>重驱动原则：申请中和验证中每轮都用同一把键、同样的参数重新调一次 Go。Go 对同键同参返回原操作的结果，
 * 所以“超时没拿到响应”“进程重启”“Go 仍在处理”都用同一个动作覆盖，不需要单独的恢复逻辑。
 * 只有当 Go 明确说结果不明（OUTCOME_UNKNOWN）时才停下，绝不换键重发，以免同一号码被发两次短信。</p>
 */
@Service
public class SmsRegistrationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SmsRegistrationWorker.class);
    /** 单轮最多处理的记录数，积压时分多轮消化。 */
    private static final int SCAN_LIMIT = 500;
    /** 幂等键前缀，带版本号以便将来协议变化时整体换一套键。 */
    private static final String KEY_PREFIX = "sms-go-v1:";
    /** 发码键后缀。 */
    private static final String SEND = "send";
    /** 验码键后缀。 */
    private static final String VERIFY = "verify";
    /** Go 在 ACTION_REQUIRED 时返回的 error_code。 */
    private static final String GO_ACTION_REQUIRED = "ACTION_REQUIRED";

    /** 办理记录数据访问。 */
    private final SmsRegistrationMapper registrations;
    /** 接码订单数据访问，只读。 */
    private final SmsActivationMapper activations;
    /** 订单服务：向号商查码回写、完成订单，复用已有逻辑。 */
    private final SmsActivationService activationService;
    /** Go 注册客户端。 */
    private final GoRegistrationClient go;
    /** 代理池，释放与读取端点。 */
    private final IpProxyService proxies;
    /** 把代理端点转成与协议层登录一致的连接串。 */
    private final ProxyResolver proxyResolver;
    /** 一轮内并发处理记录的线程池，大小为配置的并发数；线程按需创建。 */
    private final ExecutorService executor;

    /** 装配依赖，并发数取自配置。 */
    public SmsRegistrationWorker(SmsRegistrationMapper registrations, SmsActivationMapper activations,
            SmsActivationService activationService, GoRegistrationClient go, IpProxyService proxies,
            ProxyResolver proxyResolver, GoRegistrationProperties properties) {
        this.registrations = registrations;
        this.activations = activations;
        this.activationService = activationService;
        this.go = go;
        this.proxies = proxies;
        this.proxyResolver = proxyResolver;
        this.executor = Executors.newFixedThreadPool(properties.getConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "sms-registration");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 应用关闭时停止线程池。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 并发推进进行中的注册，并补偿终态的代理释放，等本轮全部完成后返回。 */
    public void tick() {
        List<SmsRegistration> active = registrations.listActive(SCAN_LIMIT);
        // 两次扫描均在提交任务前完成，避免本轮刚结束的记录又被取出释放一次。
        List<SmsRegistration> pendingRelease = registrations.listPendingProxyRelease(
                SmsRegistrationState.finishedCodes(), SCAN_LIMIT);
        List<Future<?>> futures = new ArrayList<>();
        for (SmsRegistration row : active) {
            futures.add(executor.submit(() -> advanceInTenant(row)));
        }
        for (SmsRegistration row : pendingRelease) {
            futures.add(executor.submit(() -> advanceInTenant(row)));
        }
        for (Future<?> future : futures) {
            await(future);
        }
    }

    private void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOG.warn("sms registration code=TASK_FAILED");
        }
    }

    /** 线程池线程没有租户上下文，必须按记录恢复；结束后清理，避免线程复用时串租户。 */
    private void advanceInTenant(SmsRegistration row) {
        TenantContext.set(row.getTenantId());
        try {
            advance(row);
        } catch (RuntimeException exception) {
            // 不记录异常内容：可能带出号码、验证码或代理账号密码。下一轮会重试。
            LOG.warn("sms registration id={} code=STEP_FAILED", row.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private void advance(SmsRegistration row) {
        switch (SmsRegistrationState.fromCode(row.getState())) {
            case REQUESTING -> requestCode(row);
            case WAITING_CODE -> awaitCode(row);
            case VERIFYING -> verifyCode(row);
            case REGISTERED, ACTION_REQUIRED -> releaseProxy(row);
        }
    }

    private void requestCode(SmsRegistration row) {
        SmsActivation order = activations.find(row.getSmsActivationId());
        Optional<SmsRegistrationFailure> gone = orderGone(order);
        if (gone.isPresent()) {
            fail(row, gone.get());
            return;
        }
        String proxyUrl;
        try {
            proxyUrl = proxyUrl(row);
        } catch (BusinessException exception) {
            fail(row, SmsRegistrationFailure.PROXY_UNAVAILABLE);
            return;
        }
        // 号商给的是不带加号的国际数字，Go 要求显式 + 号。
        GoRegistrationResponse response = go.requestCode(row.getTenantId(), key(row, SEND), new GoRegistrationRequest("+" + order.getPhoneNumber(),
                row.getGoProfileId(), proxyUrl, row.getAccountType() == AccountTypeCode.BUSINESS));
        if (response.kind() != GoRegistrationResponse.Kind.DONE) {
            failureOf(response).ifPresent(reason -> fail(row, reason));
            return;
        }
        GoRegistrationSnapshot snapshot = response.snapshot();
        if (snapshot.state() != GoRegistrationState.WAITING_CODE || snapshot.transactionId().isEmpty()) {
            fail(row, SmsRegistrationFailure.GO_OUTCOME_UNKNOWN);
            return;
        }
        // go_version 只在这里写一次，验码永远使用它；之后任何查询都不得改写。
        row.setGoTransactionId(snapshot.transactionId());
        row.setGoVersion(snapshot.version());
        move(row, SmsRegistrationState.WAITING_CODE, "");
    }

    /** 等码：复用订单服务向号商查一次并回写，收到码后冻结并立即验码。 */
    private void awaitCode(SmsRegistration row) {
        SmsActivationVO order = activationService.pollProvider(row.getSmsActivationId());
        switch (SmsActivationState.valueOf(order.state())) {
            case WAITING_CODE -> { /* 继续等，下一轮再查。 */ }
            case RECEIVED -> {
                if (move(row, SmsRegistrationState.VERIFYING, "")) {
                    verifyCode(row);
                }
            }
            case CANCELLED -> fail(row, SmsRegistrationFailure.SMS_CANCELLED);
            case EXPIRED -> fail(row, SmsRegistrationFailure.ORDER_EXPIRED);
            case COMPLETED -> fail(row, SmsRegistrationFailure.ORDER_NOT_WAITING);
        }
    }

    private void verifyCode(SmsRegistration row) {
        SmsActivation order = activations.find(row.getSmsActivationId());
        // 验证码在订单进入“已收码”时已由 CAS 写入且不会再被覆盖，所以每次重放取到的都是同一个码。
        GoRegistrationResponse response = go.verifyCode(row.getTenantId(), key(row, VERIFY), row.getGoTransactionId(),
                order.getSmsCode(), row.getGoVersion());
        if (response.kind() == GoRegistrationResponse.Kind.DONE) {
            settle(row, response.snapshot());
            return;
        }
        Optional<SmsRegistrationFailure> failure = failureOf(response);
        if (failure.isPresent()) {
            fail(row, failure.get());
        } else if (System.currentTimeMillis() >= order.getExpiresAt()) {
            checkAtExpiry(row);
        }
    }

    /** 订单到期仍没有明确结果：只读查询一次，已注册就记成功，否则停下交给运营。 */
    private void checkAtExpiry(SmsRegistration row) {
        GoRegistrationResponse response = go.status(row.getTenantId(), row.getGoTransactionId());
        if (response.kind() == GoRegistrationResponse.Kind.DONE && registered(response.snapshot())) {
            settle(row, response.snapshot());
        } else {
            fail(row, SmsRegistrationFailure.NO_RESULT_BEFORE_EXPIRY);
        }
    }

    /** 验码有明确结果：REGISTERED 且结果可用才算成功，其它一律视为结果不明。 */
    private void settle(SmsRegistration row, GoRegistrationSnapshot snapshot) {
        if (!registered(snapshot)) {
            fail(row, SmsRegistrationFailure.GO_OUTCOME_UNKNOWN);
            return;
        }
        // 先记注册成功，再通知号商完成：号商完成失败不能抹掉已经成功的注册事实。
        if (!move(row, SmsRegistrationState.REGISTERED, "")) {
            return;
        }
        releaseProxy(row);
        completeOrderOnce(row);
    }

    /**
     * 只在进入已注册的这一轮调用一次号商完成，失败不重试：订单保持“已收码”，办理已结束不再占用订单，
     * 运营用旧的“完成”按钮收尾。重试可能让号商重复处理同一订单。
     */
    private void completeOrderOnce(SmsRegistration row) {
        try {
            activationService.complete(row.getSmsActivationId());
        } catch (RuntimeException exception) {
            LOG.warn("sms registration id={} code=COMPLETE_UNCONFIRMED", row.getId());
        }
    }

    private static boolean registered(GoRegistrationSnapshot snapshot) {
        return snapshot.state() == GoRegistrationState.REGISTERED && snapshot.resultAvailable();
    }

    /** 订单已不是等码或已过期：不再向 Go 发请求。 */
    private static Optional<SmsRegistrationFailure> orderGone(SmsActivation order) {
        if (order.getState() != SmsActivationState.WAITING_CODE.code()) {
            return Optional.of(SmsRegistrationFailure.ORDER_NOT_WAITING);
        }
        return System.currentTimeMillis() >= order.getExpiresAt() ? Optional.of(SmsRegistrationFailure.ORDER_EXPIRED)
                : Optional.empty();
    }

    /**
     * 把非 DONE 的 Go 结果翻译成动作。返回空表示保持当前状态，下一轮用同一把键原样重放：
     * 处理中、本地限流（首次发码时尚未向外发出任何请求）、没拿到响应，都属于这一类。
     */
    private static Optional<SmsRegistrationFailure> failureOf(GoRegistrationResponse response) {
        return switch (response.kind()) {
            case IN_PROGRESS, RATE_LIMITED, NO_RESPONSE -> Optional.empty();
            case CONFLICT -> Optional.of(SmsRegistrationFailure.GO_CONFLICT);
            case UNAVAILABLE -> Optional.of(SmsRegistrationFailure.GO_UNAVAILABLE);
            case OUTCOME_UNKNOWN, DONE -> Optional.of(SmsRegistrationFailure.GO_OUTCOME_UNKNOWN);
            case REJECTED -> Optional.of(GO_ACTION_REQUIRED.equals(response.snapshot().errorCode())
                    ? SmsRegistrationFailure.GO_ACTION_REQUIRED : SmsRegistrationFailure.GO_REJECTED);
        };
    }

    /** 进入“需处理”并归还代理；失败时由后续轮次继续释放。 */
    private void fail(SmsRegistration row, SmsRegistrationFailure reason) {
        if (!move(row, SmsRegistrationState.ACTION_REQUIRED, reason.name())) {
            return;
        }
        releaseProxy(row);
    }

    /** 先归还代理再清占用标记；任一步失败都保留标记，下轮只重试释放，不再发码或验码。 */
    private void releaseProxy(SmsRegistration row) {
        if (row.getProxyId() == null) {
            return;
        }
        try {
            proxies.releaseRegistrationAllocation(row.getSmsActivationId(), row.getProxyId());
            registrations.clearReleasedProxy(row.getId(), row.getProxyId(), SmsRegistrationState.finishedCodes());
        } catch (RuntimeException exception) {
            LOG.warn("sms registration id={} code=PROXY_RELEASE_FAILED", row.getId());
        }
    }

    /** 仅当仍处于原状态时迁移，返回是否迁移成功。 */
    private boolean move(SmsRegistration row, SmsRegistrationState target, String failureCode) {
        int from = row.getState();
        row.setState(target.code());
        row.setFailureCode(failureCode);
        row.setUpdatedAt(System.currentTimeMillis());
        return registrations.transition(row, from) == 1;
    }

    /** 每次现取现拼，保证同一记录每次得到完全相同的连接串（Go 的幂等指纹包含它）。 */
    private String proxyUrl(SmsRegistration row) {
        return proxyResolver.resolve(proxies.findEndpoint(row.getProxyId())).url();
    }

    private static String key(SmsRegistration row, String kind) {
        return KEY_PREFIX + row.getTenantId() + ":" + row.getId() + ":" + kind;
    }
}
