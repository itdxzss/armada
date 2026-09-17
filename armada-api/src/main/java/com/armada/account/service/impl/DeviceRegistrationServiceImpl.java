package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationResultDTO;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.model.enums.RegistrationExecutionMode;
import com.armada.account.model.vo.DeviceRegistrationVO;
import com.armada.account.service.DeviceRegistrationService;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import com.armada.account.model.enums.DeviceRegistrationFailureKind;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 复用采购聚合；HTTP 只读取短信及接受原生回报，永不调用 Cobalt 或导入。 */
@Service
public class DeviceRegistrationServiceImpl implements DeviceRegistrationService {
    private static final long POLL_INTERVAL = 5000;
    private static final long TIMEOUT = 25 * 60 * 1000L;
    private final AccountRegistrationMapper mapper;
    private final DeviceRegistrationPermitStore store;
    private final AccountRegistrationServiceImpl registration;
    private final GrizzlySmsClient grizzly;

    /** 装配现有采购与接码服务。 */
    public DeviceRegistrationServiceImpl(AccountRegistrationMapper mapper, DeviceRegistrationPermitStore store,
            AccountRegistrationServiceImpl registration, GrizzlySmsClient grizzly) {
        this.mapper = mapper; this.store = store; this.registration = registration; this.grizzly = grizzly;
    }

    @Override public DeviceRegistrationVO start(DeviceRegistrationPermit permit) {
        requireTenant(permit);
        var existing = mapper.findByRequestId(permit.requestId());
        if (existing != null) { requireBound(existing, permit); return inspect(permit); }
        requireReplaceablePrevious(permit);
        if (System.currentTimeMillis() >= permit.expiresAt() || !registration.deviceOrderingDisabledReason().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "手机注册采购未开放或许可已过期");
        }
        String service = registration.deviceServiceCode(permit.countryId());
        registration.requireAvailableTier(service, permit.countryId(), permit.unitPrice(), 1, permit.providerId());
        try { store.createDevice(permit, service); }
        catch (DuplicateKeyException exception) {
            var concurrent = mapper.findByRequestId(permit.requestId());
            if (concurrent == null) { throw new BusinessException(ErrorCode.CONFLICT, "请重试查询同一许可"); }
            requireBound(concurrent, permit);
        }
        return inspect(permit);
    }

    @Override public DeviceRegistrationVO status(DeviceRegistrationPermit permit) {
        requireTenant(permit);
        var task = mapper.findByRequestId(permit.requestId());
        if (task == null) {
            requireReplaceablePrevious(permit);
            return snapshot(permit, "NOT_STARTED", "", "", "");
        }
        requireBound(task, permit);
        var item = onlyItem(task);
        String code = "";
        var state = AccountRegistrationState.fromCode(item.getState());
        if (state == AccountRegistrationState.WAITING_CODE || state == AccountRegistrationState.REGISTERING) {
            code = poll(item);
        }
        return view(permit, item, code);
    }

    @Override public DeviceRegistrationVO inspect(DeviceRegistrationPermit permit) {
        requireTenant(permit);
        var task = mapper.findByRequestId(permit.requestId());
        if (task == null) { return snapshot(permit, "NOT_STARTED", "", "", ""); }
        requireBound(task, permit);
        return view(permit, onlyItem(task), "");
    }

    private String poll(AccountRegistrationItem item) {
        long now = System.currentTimeMillis();
        if (now - item.getUpdatedAt() < POLL_INTERVAL || !claim(item, now)) { return ""; }
        try {
            if (now - item.getStartedAt() > TIMEOUT) {
                item.setState(AccountRegistrationState.UNKNOWN.code()); item.setFailureCode("REGISTRATION_TIMEOUT");
                save(item); return "";
            }
            var sms = grizzly.getStatus(item.getActivationId());
            String code = "";
            if (sms.state() == GrizzlySmsStatus.State.CANCELLED) {
                item.setState(AccountRegistrationState.FAILED.code()); item.setFailureCode("SMS_CANCELLED");
            } else if (sms.state() == GrizzlySmsStatus.State.RECEIVED && sms.code().isPresent()) {
                code = sms.code().orElseThrow();
                if (!code.matches("[0-9]{6}")) {
                    item.setState(AccountRegistrationState.UNKNOWN.code()); item.setFailureCode("SMS_CODE_SHAPE"); code = "";
                } else { item.setState(AccountRegistrationState.REGISTERING.code()); item.setFailureCode(""); }
            }
            save(item); return code;
        } catch (GrizzlySmsException exception) {
            item.setFailureCode("SMS_" + exception.getReason().name()); save(item); return "";
        } finally { mapper.release(item.getId(), item.getLeaseToken()); }
    }

    @Override public DeviceRegistrationVO result(DeviceRegistrationPermit permit, DeviceRegistrationResultDTO result) {
        requireTenant(permit);
        validateResult(permit, result);
        var task = mapper.findByRequestId(permit.requestId());
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        requireBound(task, permit);
        var item = onlyItem(task);
        if (!Objects.equals(Objects.requireNonNullElse(item.getPhoneNumber(), ""), result.phoneNumber())) {
            throw new BusinessException(ErrorCode.CONFLICT, "注册回报号码与当前任务不匹配");
        }
        // ACK 丢失后返回首次已落库的原生失败，不覆盖原因、不触发采购或供应商取消。
        if (isRepeatedResult(item, result)) { return view(permit, item, ""); }
        requireResultState(item, result);
        if (!claim(item, System.currentTimeMillis())) { throw new BusinessException(ErrorCode.CONFLICT, "任务处理中，请重试同一回报"); }
        try {
            if ("FAILED".equals(result.outcome())) {
                item.setState(AccountRegistrationState.FAILED.code());
                item.setFailureCode(result.failureCode());
                item.setFailureKind(result.failureKind().code());
                item.setFailureDetail(result.failureDetail());
            } else {
                item.setState("REGISTERED".equals(result.outcome()) ? AccountRegistrationState.DEVICE_REGISTERED.code()
                        : (item.getState() == AccountRegistrationState.PENDING.code() ? AccountRegistrationState.CANCELLED.code() : AccountRegistrationState.UNKNOWN.code()));
                item.setFailureCode("STOPPED".equals(result.outcome()) ? "DEVICE_STOPPED" : "");
            }
            save(item);
            if (item.getState() == AccountRegistrationState.DEVICE_REGISTERED.code()) { completeSms(item); }
            return view(permit, item, "");
        } finally { mapper.release(item.getId(), item.getLeaseToken()); }
    }

    private boolean isRepeatedResult(AccountRegistrationItem item, DeviceRegistrationResultDTO result) {
        var state = AccountRegistrationState.fromCode(item.getState());
        return (state == AccountRegistrationState.DEVICE_REGISTERED && "REGISTERED".equals(result.outcome()))
                || (state == AccountRegistrationState.FAILED && "FAILED".equals(result.outcome()) && item.getFailureKind() != null);
    }

    private void requireResultState(AccountRegistrationItem item, DeviceRegistrationResultDTO result) {
        var state = AccountRegistrationState.fromCode(item.getState());
        if (state.isTerminal() || state == AccountRegistrationState.PURCHASING || state == AccountRegistrationState.CANCELLING
                || ("REGISTERED".equals(result.outcome()) && state != AccountRegistrationState.REGISTERING)
                || ("FAILED".equals(result.outcome()) && state != AccountRegistrationState.WAITING_CODE
                    && state != AccountRegistrationState.REGISTERING)) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前任务状态不能接受该回报");
        }
    }

    private void validateResult(DeviceRegistrationPermit permit, DeviceRegistrationResultDTO result) {
        if (result == null || !Objects.equals(permit.requestId(), result.requestId())
                || !("REGISTERED".equals(result.outcome()) || "STOPPED".equals(result.outcome()) || "FAILED".equals(result.outcome()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "注册回报参数无效");
        }
        if ("FAILED".equals(result.outcome())) {
            validateFailureMetadata(result);
        } else if (result.failureKind() != null || result.failureCode() != null || result.failureDetail() != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "非失败回报不能携带失败信息");
        }
    }

    private void validateFailureMetadata(DeviceRegistrationResultDTO result) {
        if (result.phoneNumber() == null || !result.phoneNumber().matches("[1-9][0-9]{5,19}")
                || result.failureKind() == null || result.failureCode() == null
                || !result.failureCode().matches("[A-Za-z0-9_]{1,64}")
                || result.failureDetail() == null || result.failureDetail().length() > 256) {
            throw new BusinessException(ErrorCode.VALIDATION, "原生失败信息无效");
        }
    }

    private void completeSms(AccountRegistrationItem item) {
        try { grizzly.setStatus(item.getActivationId(), GrizzlyStatusUpdate.COMPLETE); }
        catch (GrizzlySmsException exception) {
            item.setFailureCode("SMS_COMPLETION_UNCONFIRMED"); save(item);
        }
    }

    private boolean claim(AccountRegistrationItem item, long now) {
        item.setLeaseToken(UUID.randomUUID().toString()); item.setLeaseUntil(now + AccountRegistrationLease.TTL.toMillis());
        return mapper.claim(item, now) == 1;
    }
    private void save(AccountRegistrationItem item) {
        item.setUpdatedAt(System.currentTimeMillis());
        if (mapper.updateClaimed(item) != 1) { throw new BusinessException(ErrorCode.CONFLICT, "任务执行租约已失效"); }
    }
    private AccountRegistrationItem onlyItem(AccountRegistrationTask task) {
        var items = mapper.listItems(task.getId());
        if (items.size() != 1) { throw new BusinessException(ErrorCode.CONFLICT, "手机注册任务结构异常"); }
        return items.get(0);
    }
    private void requireReplaceablePrevious(DeviceRegistrationPermit permit) {
        if (permit.replacesRequestId() == null) { return; }
        var previous = mapper.findByRequestId(permit.replacesRequestId());
        // 只有控端存储层可发放替代引用；旧许可未开始时本来就没有采购任务。
        if (previous == null) { return; }
        if (Objects.equals(permit.requestId(), permit.replacesRequestId())
                || !Objects.equals(previous.getExecutionMode(), RegistrationExecutionMode.IOS_DEVICE.code())
                || !Objects.equals(previous.getDeviceId(), permit.deviceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "新许可与上一笔手机任务不匹配");
        }
        var item = onlyItem(previous);
        var state = AccountRegistrationState.fromCode(item.getState());
        if (!state.canStartReplacement(item.getFailureCode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "上一笔订单尚不能更换为新许可");
        }
    }
    private void requireBound(AccountRegistrationTask task, DeviceRegistrationPermit permit) {
        if (!Objects.equals(task.getExecutionMode(), RegistrationExecutionMode.IOS_DEVICE.code())
                || !Objects.equals(task.getDeviceId(), permit.deviceId()) || !Objects.equals(task.getCountryId(), permit.countryId())
                || !Objects.equals(task.getProviderId(), permit.providerId())
                || task.getUnitPrice().compareTo(permit.unitPrice()) != 0 || !Objects.equals(task.getPurchaseBefore(), permit.expiresAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "注册许可与已存在任务不匹配");
        }
    }
    private static void requireTenant(DeviceRegistrationPermit permit) {
        if (permit == null || !Objects.equals(TenantContext.get(), permit.tenantId())) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
    }
    private DeviceRegistrationVO view(DeviceRegistrationPermit permit, AccountRegistrationItem item, String code) {
        return new DeviceRegistrationVO(permit.requestId(), AccountRegistrationState.fromCode(item.getState()).name(),
                Objects.requireNonNullElse(item.getPhoneNumber(), ""), code, permit.unitPrice(), permit.countryId(), permit.expiresAt(),
                Objects.requireNonNullElse(item.getFailureCode(), ""), permit.providerId(), permit.replacesRequestId(),
                item.getActualCost(), item.getCurrency(), item.getPurchaseAttempts(), item.getNextPurchaseAt(),
                DeviceRegistrationFailureKind.fromCode(item.getFailureKind()), Objects.requireNonNullElse(item.getFailureDetail(), ""));
    }
    private DeviceRegistrationVO snapshot(DeviceRegistrationPermit permit, String state, String phone, String code, String failure) {
        return new DeviceRegistrationVO(permit.requestId(), state, phone, code, permit.unitPrice(), permit.countryId(), permit.expiresAt(),
                failure, permit.providerId(), permit.replacesRequestId(), null, null, 0, null, null, "");
    }
}
