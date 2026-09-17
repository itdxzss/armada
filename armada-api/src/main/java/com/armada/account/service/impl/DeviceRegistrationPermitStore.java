package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.mapper.DeviceRegistrationPermitMapper;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 设备许可与任务开始共用一把行锁；短事务内不访问接码平台。 */
@Service
public class DeviceRegistrationPermitStore {
    private final DeviceRegistrationPermitMapper permits;
    private final AccountRegistrationMapper tasks;
    private final AccountRegistrationStore store;
    /** 装配同一注册域的持久化依赖。 */
    public DeviceRegistrationPermitStore(DeviceRegistrationPermitMapper permits, AccountRegistrationMapper tasks,
            AccountRegistrationStore store) { this.permits = permits; this.tasks = tasks; this.store = store; }

    /** 手机明确发起时锁定设备；在途任务只能接续，已确认结束后才可创建下一笔。 */
    @Transactional(rollbackFor = Exception.class)
    public DeviceRegistrationPermit prepareFromDevice(DeviceRegistrationPermit next) {
        var current = permits.lock(next.deviceId());
        var latest = tasks.latestDeviceTask(next.deviceId());
        var requested = tasks.findByRequestId(next.requestId());
        if (requested != null && (latest == null || !Objects.equals(requested.getDeviceId(), next.deviceId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "该请求属于其他设备");
        }
        if (requested != null && (!requested.getCountryId().equals(next.countryId())
                || requested.getUnitPrice().compareTo(next.unitPrice()) != 0)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一请求不能改变国家或价格");
        }
        DeviceRegistrationPermit selected = next;
        if (latest != null && (requested != null || !canReplace(latest))) {
            selected = new DeviceRegistrationPermit(next.tenantId(), next.deviceId(), latest.getRequestId(), latest.getCountryId(),
                    latest.getUnitPrice(), latest.getPurchaseBefore(), latest.getProviderId(), null);
        } else if (current != null && current.requestId().equals(next.requestId())) {
            if (!current.countryId().equals(next.countryId()) || current.unitPrice().compareTo(next.unitPrice()) != 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "同一请求不能改变国家或价格");
            }
            selected = current.expiresAt() > System.currentTimeMillis() ? current : next;
        } else {
            selected = new DeviceRegistrationPermit(next.tenantId(), next.deviceId(), next.requestId(), next.countryId(),
                    next.unitPrice(), next.expiresAt(), null, latest == null ? null : latest.getRequestId());
        }
        if (current == null) { permits.insert(selected, System.currentTimeMillis()); }
        else { permits.update(selected, System.currentTimeMillis()); }
        return selected;
    }

    private boolean canReplace(AccountRegistrationTask task) {
        var items = tasks.listItems(task.getId());
        if (items.size() != 1) { throw new BusinessException(ErrorCode.CONFLICT, "旧任务明细异常"); }
        var item = items.get(0);
        var state = AccountRegistrationState.fromCode(item.getState());
        return state.canStartReplacement(item.getFailureCode());
    }

    /** 保存明确的一次授权，保留所有历史任务；同请求不得改参数。 */
    @Transactional(rollbackFor = Exception.class)
    public DeviceRegistrationPermit prepare(DeviceRegistrationPermit next) {
        var current = permits.lock(next.deviceId());
        if (current != null && current.requestId().equals(next.requestId())) {
            if (!sameSelection(current, next) || !Objects.equals(current.providerId(), next.providerId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该许可已使用不同参数，请刷新当前许可");
            }
            return current;
        }
        if (tasks.findByRequestId(next.requestId()) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "请求ID已用于历史任务");
        }
        var previous = current == null ? tasks.latestDeviceTask(next.deviceId()) : tasks.findByRequestId(current.requestId());
        if (previous != null && !canReplace(previous)) {
            throw new BusinessException(ErrorCode.CONFLICT, "旧任务仍在进行或结果未确认，请先处理旧任务");
        }
        String replaces = current != null ? current.requestId() : previous == null ? null : previous.getRequestId();
        var saved = new DeviceRegistrationPermit(next.tenantId(), next.deviceId(), next.requestId(), next.countryId(),
                next.unitPrice(), next.expiresAt(), next.providerId(), replaces);
        if (current == null) { permits.insert(saved, System.currentTimeMillis()); }
        else { permits.update(saved, System.currentTimeMillis()); }
        return saved;
    }

    /** 锁内再次检查当前授权，防止准备新许可与旧请求开始同时产生采购。 */
    @Transactional(rollbackFor = Exception.class)
    public void createDevice(DeviceRegistrationPermit permit, String serviceCode) {
        var current = permits.lock(permit.deviceId());
        if (current == null || !current.requestId().equals(permit.requestId()) || !sameSelection(current, permit)
                || System.currentTimeMillis() >= current.expiresAt()) {
            throw new BusinessException(ErrorCode.CONFLICT, "采购许可已更新或过期，请刷新");
        }
        store.createDevice(permit, serviceCode);
    }

    private static boolean sameSelection(DeviceRegistrationPermit first, DeviceRegistrationPermit second) {
        return first.tenantId() == second.tenantId() && first.deviceId().equals(second.deviceId())
                && first.countryId().equals(second.countryId()) && first.unitPrice().compareTo(second.unitPrice()) == 0
                && first.expiresAt() == second.expiresAt();
    }
}
