package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.mapper.DeviceRegistrationPermitMapper;
import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationBeginDTO;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationSetupDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.account.service.DeviceRegistrationPermitService;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 实时报价校验在事务之外，采购意图由短事务串行化。 */
@Service
public class DeviceRegistrationPermitServiceImpl implements DeviceRegistrationPermitService {
    private static final long MAX_VALIDITY = 24 * 60 * 60 * 1000L;
    private final DeviceRegistrationPermitMapper permits;
    private final AccountRegistrationMapper tasks;
    private final DeviceRegistrationPermitStore store;
    private final AccountRegistrationServiceImpl registration;
    /** 装配许可和现有报价能力。 */
    public DeviceRegistrationPermitServiceImpl(DeviceRegistrationPermitMapper permits, AccountRegistrationMapper tasks,
            DeviceRegistrationPermitStore store, AccountRegistrationServiceImpl registration) {
        this.permits = permits; this.tasks = tasks; this.store = store; this.registration = registration;
    }
    @Override public DeviceRegistrationPermit current(DeviceRegistrationIdentity identity) {
        if (!Objects.equals(TenantContext.get(), identity.tenantId())) { throw new BusinessException(ErrorCode.TENANT_MISSING); }
        var permit = permits.find(identity.deviceId());
        if (permit == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "请先在控端创建本设备的单次注册许可"); }
        var task = tasks.findByRequestId(permit.requestId());
        return task == null ? permit : withProvider(permit, task.getProviderId());
    }
    @Override public DeviceRegistrationPermit begin(DeviceRegistrationIdentity identity, DeviceRegistrationBeginDTO request) {
        if (!Objects.equals(TenantContext.get(), identity.tenantId())) { throw new BusinessException(ErrorCode.TENANT_MISSING); }
        if (request == null) { throw new BusinessException(ErrorCode.VALIDATION); }
        requireUuid(request.requestId());
        var price = request.unitPrice();
        if (price == null || price.signum() <= 0 || price.scale() > 12 || price.precision() > 30
                || price.precision() - price.scale() > 18) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择有效单价");
        }
        // 已有任务恢复时不依赖瞬时报价库存；实际新建仍由start校验报价与采购开关。
        registration.deviceServiceCode(request.countryId());
        var next = new DeviceRegistrationPermit(identity.tenantId(), identity.deviceId(), request.requestId(),
                request.countryId(), price, System.currentTimeMillis() + MAX_VALIDITY, null, null);
        try { return store.prepareFromDevice(next); }
        catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT, "设备请求处理中，请重试同一请求"); }
    }
    @Override public DeviceRegistrationPermit prepare(DeviceRegistrationSetupDTO request) {
        Long tenant = TenantContext.get();
        if (tenant == null || request == null) { throw new BusinessException(ErrorCode.TENANT_MISSING); }
        requireUuid(request.deviceId()); requireUuid(request.requestId());
        long now = System.currentTimeMillis();
        if (request.unitPrice() == null || request.unitPrice().signum() <= 0 || request.unitPrice().scale() > 12
                || request.unitPrice().precision() > 30 || request.unitPrice().precision() - request.unitPrice().scale() > 18
                || request.expiresAt() <= now || request.expiresAt() - now > MAX_VALIDITY) {
            throw new BusinessException(ErrorCode.VALIDATION, "单价必须有效，许可有效期不能超过24小时");
        }
        String service = registration.deviceServiceCode(request.countryId());
        registration.requireAvailableTier(service, request.countryId(), request.unitPrice(), 1, request.providerId());
        var permit = new DeviceRegistrationPermit(tenant, request.deviceId(), request.requestId(), request.countryId(),
                request.unitPrice(), request.expiresAt(), request.providerId(), null);
        try { return store.prepare(permit); }
        catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT, "许可并发更新，请刷新后核对"); }
    }
    @Override public DeviceRegistrationPermit select(DeviceRegistrationPermit permit, DeviceRegistrationStartDTO request) {
        if (request == null || !permit.requestId().equals(request.requestId()) || request.providerId() == null
                || !(request.providerId().isEmpty() || request.providerId().matches("[1-9][0-9]{0,31}"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "请刷新许可并确认本次商家");
        }
        return withProvider(permit, request.providerId().isEmpty() ? null : request.providerId());
    }
    @Override public List<GrizzlyPriceTier> options(DeviceRegistrationPermit permit) {
        return registration.priceTiers(permit.countryId()).stream()
                .filter(tier -> tier.cost().compareTo(permit.unitPrice()) == 0 && tier.count() > 0).toList();
    }
    private static DeviceRegistrationPermit withProvider(DeviceRegistrationPermit permit, String provider) {
        return new DeviceRegistrationPermit(permit.tenantId(), permit.deviceId(), permit.requestId(), permit.countryId(),
                permit.unitPrice(), permit.expiresAt(), provider, permit.replacesRequestId());
    }
    private static void requireUuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) { throw new IllegalArgumentException(); } }
        catch (IllegalArgumentException exception) { throw new BusinessException(ErrorCode.VALIDATION, "设备与请求标识必须为规范UUID"); }
    }
}
