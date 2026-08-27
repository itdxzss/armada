package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedSink;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 协议群邀请链接变更事件到 group 域服务的 adapter。 */
@Service
public class GroupInviteLinkChangedSinkAdapter implements ProtocolGroupInviteLinkChangedSink {

    private final GroupInviteLinkService service;
    private final GroupMetadataSyncTaskMapper taskMapper;
    private final GroupBatchTaskItemMapper batchItemMapper;
    private final AccountMapper accountMapper;

    /** 创建群邀请链接变更事件 adapter。 */
    public GroupInviteLinkChangedSinkAdapter(
            GroupInviteLinkService service,
            GroupMetadataSyncTaskMapper taskMapper,
            GroupBatchTaskItemMapper batchItemMapper,
            AccountMapper accountMapper) {
        this.service = service;
        this.taskMapper = taskMapper;
        this.batchItemMapper = batchItemMapper;
        this.accountMapper = accountMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleInviteLinkChanged(ProtocolGroupInviteLinkChangedEvent event) {
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
                service.applyCurrentInvite(new GroupInviteLinkObservation(
                        event.eventId(),
                        null,
                        event.groupJid(),
                        event.inviteCode(),
                        ProtocolBackend.valueOf(event.protocolBackend()),
                        event.source(),
                        event.occurredAt()));
                if (event.commandId() != null && !event.commandId().isBlank()) {
                    taskMapper.markScopeCompleted(event.commandId(), 2, event.occurredAt());
                    batchItemMapper.markScopeCompleted(event.commandId(), 2, event.occurredAt());
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
                    "历史无归属账号不能消费用户私有群邀请链接事件");
        }
        return account.getOwnerUserId();
    }
}
