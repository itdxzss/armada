package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.AccountRegistrationCreateDTO;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.service.AccountGroupService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册任务的短事务写入；任何HTTP调用都在此组件外执行。 */
@Service
public class AccountRegistrationStore {
    /** 注册聚合数据访问。 */
    private final AccountRegistrationMapper mapper;
    /** 复用现有分组校验。 */
    private final AccountGroupService groups;
    /** 装配持久化依赖。 */
    public AccountRegistrationStore(AccountRegistrationMapper mapper, AccountGroupService groups) {
        this.mapper = mapper;
        this.groups = groups;
    }
    /** 创建或读取同键同参数任务；唯一键处理并发重放。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(AccountRegistrationCreateDTO request, String serviceCode) {
        AccountRegistrationTask existing = mapper.findByRequestId(request.requestId());
        if (existing != null) { requireSame(existing, request); return existing.getId(); }
        groups.requireExisting(request.accountGroupId());
        AccountRegistrationTask task = new AccountRegistrationTask();
        task.setRequestId(request.requestId());
        task.setServiceCode(serviceCode);
        task.setCountryId(request.countryId());
        task.setUnitPrice(request.unitPrice());
        task.setQuantity(request.quantity());
        task.setAccountGroupId(request.accountGroupId());
        task.setAccountType(request.accountType());
        task.setIpAllocationMode(request.ipAllocationMode());
        task.setIpRegion(request.ipRegion());
        task.setCancelRequested(false);
        long now = System.currentTimeMillis();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        mapper.insertTask(task);
        List<AccountRegistrationItem> items = new ArrayList<>();
        for (int ordinal = 1; ordinal <= request.quantity(); ordinal++) {
            AccountRegistrationItem item = new AccountRegistrationItem();
            item.setTaskId(task.getId());
            item.setOrdinal(ordinal);
            item.setState(AccountRegistrationState.PENDING.code());
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            items.add(item);
        }
        mapper.insertItems(items);
        return task.getId();
    }
    /** 停止尚未采购条目；与采购前条件保存互斥，已采购的不会被误取消。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        long now = System.currentTimeMillis();
        if (mapper.requestCancel(id, now) != 1) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        mapper.cancelPending(id, now);
    }
    /** 幂等键不能被复用为另一张采购订单。 */
    public static void requireSame(AccountRegistrationTask task, AccountRegistrationCreateDTO request) {
        boolean same = Objects.equals(task.getCountryId(), request.countryId())
                && task.getUnitPrice().compareTo(request.unitPrice()) == 0
                && Objects.equals(task.getQuantity(), request.quantity())
                && Objects.equals(task.getAccountGroupId(), request.accountGroupId())
                && Objects.equals(task.getAccountType(), request.accountType())
                && Objects.equals(task.getIpAllocationMode(), request.ipAllocationMode())
                && Objects.equals(task.getIpRegion(), request.ipRegion());
        if (!same) { throw new BusinessException(ErrorCode.CONFLICT, "创建请求ID已经用于不同参数"); }
    }
}
