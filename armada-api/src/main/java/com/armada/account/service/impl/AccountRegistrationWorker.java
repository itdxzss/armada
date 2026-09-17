package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.model.enums.RegistrationExecutionMode;
import com.armada.account.service.AccountImportService;
import com.armada.account.service.AccountService;
import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.registration.cobalt.CobaltRegistrationException;
import com.armada.platform.registration.cobalt.CobaltRegistrationFailure;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationSnapshot;
import com.armada.platform.registration.cobalt.model.CobaltRegistrationState;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 固定次数接码注册worker，每tick推进一条，外部副作用前先保存意图。 */
@Service
public class AccountRegistrationWorker {
    /** 不记录原始异常、验证码、密钥或完整号码。 */
    private static final Logger LOG = LoggerFactory.getLogger(AccountRegistrationWorker.class);
    /** 每个号码最多请求 50 次，只有明确无号码才再次请求。 */
    public static final int MAX_PURCHASE_ATTEMPTS = 50;
    /** 从无号码响应保存后等待五秒，不占用执行线程。 */
    private static final long PURCHASE_RETRY_INTERVAL = Duration.ofSeconds(5).toMillis();
    /** 接码/注册等待上限；到期保留已购订单供核对，不补买。 */
    private static final long REGISTRATION_TIMEOUT = Duration.ofMinutes(25).toMillis();
    /** 导入后等待协议首次结果的上限。 */
    private static final long ONLINE_TIMEOUT = Duration.ofMinutes(30).toMillis();
    /** 供应商无时区时间仅用于求取消窗口的相对长度。 */
    private static final DateTimeFormatter ACTIVATION_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 避免请求传输与秒级供应商时间造成提前取消。 */
    private static final long CANCEL_MARGIN = Duration.ofSeconds(5).toMillis();
    /** 现有账号登录状态在线值。 */
    private static final int ONLINE = 1;
    /** 注册聚合SQL。 */
    private final AccountRegistrationMapper mapper;
    /** 分布式互斥。 */
    private final AccountRegistrationLease lease;
    /** 采购前门禁。 */
    private final AccountRegistrationServiceImpl service;
    /** 任务取消短事务。 */
    private final AccountRegistrationStore store;
    /** 接码平台。 */
    private final GrizzlySmsClient grizzly;
    /** 注册进程。 */
    private final CobaltRegistrationClient cobalt;
    /** 凭据入库短事务。 */
    private final AccountRegistrationImportService importService;
    /** 现有导入首次登录结算。 */
    private final AccountImportService imports;
    /** 现有账号当前在线状态。 */
    private final AccountService accounts;
    /** 装配各已存在服务及本任务事务边界。 */
    public AccountRegistrationWorker(AccountRegistrationMapper mapper, AccountRegistrationLease lease,
            AccountRegistrationServiceImpl service, AccountRegistrationStore store, GrizzlySmsClient grizzly,
            CobaltRegistrationClient cobalt, AccountRegistrationImportService importService,
            AccountImportService imports, AccountService accounts) {
        this.mapper = mapper;
        this.lease = lease;
        this.service = service;
        this.store = store;
        this.grizzly = grizzly;
        this.cobalt = cobalt;
        this.importService = importService;
        this.imports = imports;
        this.accounts = accounts;
    }

    /** 跨租户扫描仅拿ID，然后恢复租户；租约失败时不执行HTTP。 */
    public void tick() {
        String token;
        try { token = lease.acquire(); }
        catch (RuntimeException exception) { LOG.warn("registration code=LEASE_UNAVAILABLE"); return; }
        if (token.isEmpty()) { return; }
        Long previousTenant = TenantContext.get();
        try {
            var next = mapper.nextWork(System.currentTimeMillis());
            if (next == null) { return; }
            TenantContext.set(next.getTenantId());
            advance(next.getId(), token);
        } catch (RuntimeException exception) {
            LOG.warn("registration code=TICK_FAILED");
        } finally {
            if (previousTenant == null) { TenantContext.clear(); } else { TenantContext.set(previousTenant); }
            try { lease.release(token); }
            catch (RuntimeException exception) { LOG.warn("registration code=LEASE_RELEASE_UNCONFIRMED"); }
        }
    }

    private void advance(Long id, String token) {
        AccountRegistrationItem item = mapper.findItem(id);
        if (item == null || AccountRegistrationState.fromCode(item.getState()).isTerminal()) { return; }
        long now = System.currentTimeMillis();
        item.setLeaseToken(token);
        item.setLeaseUntil(now + AccountRegistrationLease.TTL.toMillis());
        if (mapper.claim(item, now) != 1) { return; }
        try {
            AccountRegistrationTask task = mapper.findTask(item.getTaskId());
            if (task == null) { finish(item, AccountRegistrationState.UNKNOWN, "TASK_MISSING"); return; }
            dispatch(task, item);
        } catch (RuntimeException exception) {
            LOG.warn("registration itemId={} code=STEP_UNCONFIRMED", item.getId());
        } finally { mapper.release(item.getId(), token); }
    }

    private void dispatch(AccountRegistrationTask task, AccountRegistrationItem item) {
        if (Objects.equals(task.getExecutionMode(), RegistrationExecutionMode.IOS_DEVICE.code())
                && (item.getState() == AccountRegistrationState.IMPORTING.code() || item.getState() == AccountRegistrationState.WAITING_ONLINE.code())) {
            finish(item, AccountRegistrationState.UNKNOWN, "DEVICE_STATE_INVALID"); return;
        }
        switch (AccountRegistrationState.fromCode(item.getState())) {
            case PENDING -> purchase(task, item);
            case PURCHASING -> { finish(item, AccountRegistrationState.UNKNOWN, "PURCHASE_RESULT_UNKNOWN"); store.cancel(task.getId()); }
            case WAITING_CODE, REGISTERING -> registration(task, item);
            case IMPORTING -> importRegistered(task, item);
            case WAITING_ONLINE -> settleOnline(item);
            case CANCELLING -> cancelMispriced(task, item);
            default -> { /* 已终态不再执行外部请求。 */ }
        }
    }

    private void purchase(AccountRegistrationTask task, AccountRegistrationItem item) {
        if (Boolean.TRUE.equals(task.getCancelRequested())) { finish(item, AccountRegistrationState.CANCELLED, ""); return; }
        if (item.getNextPurchaseAt() != null && System.currentTimeMillis() < item.getNextPurchaseAt()) { return; }
        if (item.getPurchaseAttempts() >= MAX_PURCHASE_ATTEMPTS) {
            finish(item, AccountRegistrationState.FAILED, "SMS_NO_NUMBERS_EXHAUSTED"); return;
        }
        boolean device = Objects.equals(task.getExecutionMode(), RegistrationExecutionMode.IOS_DEVICE.code());
        if (device && (task.getPurchaseBefore() == null || System.currentTimeMillis() >= task.getPurchaseBefore())) {
            finish(item, AccountRegistrationState.FAILED, "DEVICE_PERMIT_EXPIRED"); return;
        }
        if (!(device ? service.deviceOrderingDisabledReason() : service.orderingDisabledReason()).isEmpty()) { return; }
        try {
            var request = selectedPriceRequest(task);
            if (device && System.currentTimeMillis() >= task.getPurchaseBefore()) {
                finish(item, AccountRegistrationState.FAILED, "DEVICE_PERMIT_EXPIRED"); return;
            }
            item.setState(AccountRegistrationState.PURCHASING.code());
            item.setStartedAt(System.currentTimeMillis());
            item.setPurchaseAttempts(item.getPurchaseAttempts() + 1);
            item.setNextPurchaseAt(null);
            item.setFailureCode("");
            save(item);
            var activation = grizzly.acquireNumber(request);
            item.setActivationId(activation.activationId());
            item.setPhoneNumber(activation.phoneNumber());
            item.setActualCost(activation.cost());
            item.setCurrency(activation.currency());
            if (activation.cost().compareTo(task.getUnitPrice()) != 0) {
                item.setCancelAfter(cancellationTime(activation, System.currentTimeMillis()));
                finish(item, AccountRegistrationState.CANCELLING, "PURCHASE_PRICE_MISMATCH");
                store.cancel(task.getId());
                return;
            }
            if (!device) { item.setRegistrationId("reg_" + item.getTenantId() + "_" + item.getId()); }
            finish(item, AccountRegistrationState.WAITING_CODE, "");
        } catch (GrizzlySmsException exception) {
            if (!exception.isOutcomeUnknown() && exception.getReason() == GrizzlySmsFailure.NO_NUMBERS
                    && item.getState() == AccountRegistrationState.PURCHASING.code()) {
                if (item.getPurchaseAttempts() < MAX_PURCHASE_ATTEMPTS) {
                    item.setNextPurchaseAt(System.currentTimeMillis() + PURCHASE_RETRY_INTERVAL);
                    finish(item, AccountRegistrationState.PENDING, "SMS_NO_NUMBERS_RETRY");
                } else { finish(item, AccountRegistrationState.FAILED, "SMS_NO_NUMBERS_EXHAUSTED"); }
                return;
            }
            finish(item, exception.isOutcomeUnknown() ? AccountRegistrationState.UNKNOWN : AccountRegistrationState.FAILED,
                    "SMS_" + exception.getReason().name());
            if (exception.isOutcomeUnknown()) { store.cancel(task.getId()); }
        }
    }

    private GrizzlyNumberRequest selectedPriceRequest(AccountRegistrationTask task) {
        // 自动模式只限定价格，由平台选商家；手动指定时保留该商家约束，不做隐式回退。
        var providers = task.getProviderId() == null ? List.<String>of() : List.of(task.getProviderId());
        var minimum = task.getProviderId() == null ? task.getUnitPrice() : null;
        return new GrizzlyNumberRequest(task.getServiceCode(), task.getCountryId(), task.getUnitPrice(),
                new GrizzlyNumberRequest.Options(minimum, providers, List.of(), List.of()));
    }

    private long cancellationTime(GrizzlyActivation activation, long receivedAt) {
        var details = activation.details();
        if (details.activationTime().isEmpty() || details.activationCancel().isEmpty()) { return receivedAt; }
        try {
            long delay = Duration.between(LocalDateTime.parse(details.activationTime().orElseThrow(), ACTIVATION_TIME),
                    LocalDateTime.parse(details.activationCancel().orElseThrow(), ACTIVATION_TIME)).toMillis();
            if (delay >= 0 && delay < REGISTRATION_TIMEOUT) { return receivedAt + delay + CANCEL_MARGIN; }
        } catch (DateTimeParseException exception) {
            // 无法确认窗口时只按实时订单状态申请一次；失败后保留待核对，不猜时区或无限重试。
            return receivedAt;
        }
        return receivedAt;
    }

    private void cancelMispriced(AccountRegistrationTask task, AccountRegistrationItem item) {
        store.cancel(task.getId());
        long now = System.currentTimeMillis();
        if (now - item.getStartedAt() > REGISTRATION_TIMEOUT) {
            finish(item, AccountRegistrationState.UNKNOWN, "PURCHASE_CANCELLATION_TIMEOUT"); return;
        }
        if (item.getCancelAfter() != null && now < item.getCancelAfter()) { return; }
        try {
            var status = grizzly.getStatus(item.getActivationId());
            if (status.state() == GrizzlySmsStatus.State.CANCELLED) {
                finish(item, AccountRegistrationState.FAILED, "PURCHASE_PRICE_MISMATCH_CANCELLED"); return;
            }
            if (status.state() == GrizzlySmsStatus.State.RECEIVED || status.previousCode().isPresent()) {
                finish(item, AccountRegistrationState.UNKNOWN, "PURCHASE_CANCELLATION_CODE_RECEIVED"); return;
            }
            grizzly.setStatus(item.getActivationId(), GrizzlyStatusUpdate.CANCEL);
            finish(item, AccountRegistrationState.FAILED, "PURCHASE_PRICE_MISMATCH_CANCELLED");
        } catch (GrizzlySmsException exception) {
            finish(item, AccountRegistrationState.UNKNOWN, "PURCHASE_CANCELLATION_UNCONFIRMED");
        }
    }

    private void registration(AccountRegistrationTask task, AccountRegistrationItem item) {
        if (System.currentTimeMillis() - item.getStartedAt() > REGISTRATION_TIMEOUT) {
            finish(item, AccountRegistrationState.UNKNOWN, "REGISTRATION_TIMEOUT"); return;
        }
        if (Objects.equals(task.getExecutionMode(), RegistrationExecutionMode.IOS_DEVICE.code())) { return; }
        try {
            CobaltRegistrationSnapshot snapshot;
            try { snapshot = cobalt.status(item.getRegistrationId()); }
            catch (CobaltRegistrationException exception) {
                if (exception.getReason() != CobaltRegistrationFailure.NOT_FOUND) { throw exception; }
                // 稳定ID幂等创建；请求未知时下轮先查询，绝不生成另一个注册ID。
                snapshot = cobalt.create(item.getRegistrationId(), item.getPhoneNumber(), task.getAccountType());
            }
            if (!Objects.equals(snapshot.phoneNumber(), item.getPhoneNumber())) {
                finish(item, AccountRegistrationState.UNKNOWN, "COBALT_IDENTITY_MISMATCH"); return;
            }
            handleSnapshot(item, snapshot);
        } catch (CobaltRegistrationException exception) {
            item.setFailureCode("COBALT_" + exception.getReason().name());
            save(item);
        } catch (GrizzlySmsException exception) {
            item.setFailureCode("SMS_" + exception.getReason().name());
            save(item);
        }
    }

    private void handleSnapshot(AccountRegistrationItem item, CobaltRegistrationSnapshot snapshot) {
        switch (snapshot.state()) {
            case REGISTERED -> {
                finish(item, AccountRegistrationState.IMPORTING, "");
                try { grizzly.setStatus(item.getActivationId(), GrizzlyStatusUpdate.COMPLETE); }
                catch (GrizzlySmsException exception) { item.setFailureCode("SMS_COMPLETION_UNCONFIRMED"); save(item); }
            }
            case WAITING_CODE -> submitReceivedCode(item);
            case FAILED -> finish(item, AccountRegistrationState.FAILED, "COBALT_REGISTRATION_FAILED");
            case UNKNOWN -> finish(item, AccountRegistrationState.UNKNOWN, "COBALT_RESULT_UNKNOWN");
            case CANCELLED -> finish(item, AccountRegistrationState.FAILED, "COBALT_CANCELLED");
            case QUEUED, REQUESTING_SMS, VERIFYING -> { /* 查询确认中；没有新采购或重发动作。 */ }
        }
    }

    private void submitReceivedCode(AccountRegistrationItem item) {
        var sms = grizzly.getStatus(item.getActivationId());
        if (sms.state() == GrizzlySmsStatus.State.CANCELLED) {
            finish(item, AccountRegistrationState.FAILED, "SMS_CANCELLED"); return;
        }
        if (sms.state() != GrizzlySmsStatus.State.RECEIVED || sms.code().isEmpty()) { return; }
        item.setState(AccountRegistrationState.REGISTERING.code());
        save(item);
        // OTP仅在本次栈中；若请求结果未知，下轮查询同一注册ID，可幂等重交同码。
        var result = cobalt.submitCode(item.getRegistrationId(), sms.code().orElseThrow());
        if (result.state() != CobaltRegistrationState.WAITING_CODE) { handleSnapshot(item, result); }
    }

    private void importRegistered(AccountRegistrationTask task, AccountRegistrationItem item) {
        if (System.currentTimeMillis() - item.getStartedAt() > ONLINE_TIMEOUT) {
            finish(item, AccountRegistrationState.UNKNOWN, "ACCOUNT_IMPORT_TIMEOUT"); return;
        }
        try {
            var credential = cobalt.exportSix(item.getRegistrationId());
            importService.importOne(task, item, credential);
        } catch (CobaltRegistrationException exception) {
            item.setFailureCode("COBALT_EXPORT_" + exception.getReason().name()); save(item);
        } catch (BusinessException exception) {
            finish(item, AccountRegistrationState.UNKNOWN, "ACCOUNT_IMPORT_UNCONFIRMED");
        }
    }

    private void settleOnline(AccountRegistrationItem item) {
        Integer loginState = accounts.getLoginStatesByIds(List.of(item.getAccountId())).get(item.getAccountId());
        if (Integer.valueOf(ONLINE).equals(loginState)) { finish(item, AccountRegistrationState.SUCCEEDED, ""); return; }
        AccountImportDetailQuery query = new AccountImportDetailQuery();
        query.setBatchId(item.getImportBatchId());
        query.setPageSize(1);
        var rows = imports.listDetails(query).list();
        if (!rows.isEmpty() && rows.get(0).loginResult() != null && rows.get(0).loginResult() > ONLINE) {
            finish(item, AccountRegistrationState.FAILED, "ANDROID_LOGIN_FAILED"); return;
        }
        if (System.currentTimeMillis() - item.getStartedAt() > ONLINE_TIMEOUT) {
            finish(item, AccountRegistrationState.UNKNOWN, "ANDROID_LOGIN_UNCONFIRMED");
        }
    }

    private void finish(AccountRegistrationItem item, AccountRegistrationState state, String failureCode) {
        item.setState(state.code());
        item.setFailureCode(failureCode);
        save(item);
    }

    private void save(AccountRegistrationItem item) {
        item.setUpdatedAt(System.currentTimeMillis());
        if (mapper.updateClaimed(item) != 1) { throw new BusinessException(ErrorCode.CONFLICT, "注册执行租约已失效"); }
    }
}
