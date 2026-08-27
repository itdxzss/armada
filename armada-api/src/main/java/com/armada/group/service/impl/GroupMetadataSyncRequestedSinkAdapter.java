package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 协议单群详情同步事件到群任务状态机的 adapter。 */
@Service
public class GroupMetadataSyncRequestedSinkAdapter
        implements ProtocolGroupMetadataSyncRequestedSink {

    private final GroupLinkMapper groupLinkMapper;
    private final AccountMapper accountMapper;
    private final GroupMetadataSyncTaskService taskService;

    /** 创建事件 adapter。 */
    public GroupMetadataSyncRequestedSinkAdapter(
            GroupLinkMapper groupLinkMapper,
            AccountMapper accountMapper,
            GroupMetadataSyncTaskService taskService) {
        this.groupLinkMapper = groupLinkMapper;
        this.accountMapper = accountMapper;
        this.taskService = taskService;
    }

    @Override
    public void handleGroupMetadataSyncRequested(ProtocolGroupMetadataSyncRequestedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Account account = accountMapper.selectActiveById(event.accountId());
            if (!currentProtocolBinding(account, event.protocolAccountId())) {
                return;
            }
            Long ownerUserId = requireOwner(account);
            try (DataScopeContext.Scope ignored =
                         DataScopeContext.open(DataScope.self(ownerUserId))) {
                Long groupLinkId = groupLinkMapper.selectActiveIdByGroupJid(
                        event.groupJid(), ownerUserId);
                if (groupLinkId != null) {
                    taskService.enqueue(
                            groupLinkId,
                            GroupMetadataSyncTrigger.valueOf(event.trigger()),
                            event.occurredAt());
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

    private static boolean currentProtocolBinding(Account account, String eventProtocolAccountId) {
        return account != null
                && eventProtocolAccountId != null
                && account.getProtocolAccountId() != null
                && account.getProtocolAccountId().equals(eventProtocolAccountId.trim());
    }

    private static Long requireOwner(Account account) {
        if (account.getOwnerUserId() == null) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "历史无归属账号不能创建用户私有群同步任务");
        }
        return account.getOwnerUserId();
    }
}
