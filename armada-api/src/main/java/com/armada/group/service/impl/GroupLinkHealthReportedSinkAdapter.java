package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import com.armada.group.service.GroupLinkHealthReportService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.service.PullTaskGroupBanTerminationService;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 协议群组健康检测事件到 group 域服务的 adapter。
 */
@Service
public class GroupLinkHealthReportedSinkAdapter implements ProtocolGroupHealthReportedSink {

    private static final String BANNED = "BANNED";
    private static final String CHAT_SUSPENDED = "CHAT_SUSPENDED";
    private static final String CHAT_TERMINATED = "CHAT_TERMINATED";

    private final GroupLinkHealthReportService service;
    private final PullTaskGroupBanTerminationService banTerminationService;
    private final AccountMapper accountMapper;

    /**
     * 创建群组健康事件 adapter。
     *
     * @param service 群链接健康检测回报落库服务
     * @param banTerminationService 普通拉群任务单群封禁终止服务
     */
    public GroupLinkHealthReportedSinkAdapter(
            GroupLinkHealthReportService service,
            PullTaskGroupBanTerminationService banTerminationService,
            AccountMapper accountMapper) {
        this.service = service;
        this.banTerminationService = banTerminationService;
        this.accountMapper = accountMapper;
    }

    /**
     * 处理协议群组健康检测事件。
     *
     * @param event platform.kafka 已解析的群组健康检测事件
     */
    @Override
    public void handleHealthReported(ProtocolGroupHealthReportedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Account executionAccount = accountMapper.selectActiveByProtocolAccountId(
                    event.protocolAccountId());
            if (executionAccount == null) {
                return;
            }
            Long ownerUserId = requireOwner(executionAccount);
            try (DataScopeContext.Scope ignored =
                         DataScopeContext.open(DataScope.self(ownerUserId))) {
                Optional<Long> resolvedGroupLinkId = service.applyHealthReported(toGroupEvent(event));
                if (isExplicitGroupBan(event.health(), event.errorCode())) {
                    resolvedGroupLinkId.ifPresent(groupLinkId ->
                            banTerminationService.terminateBannedGroup(
                                    event.tenantId(), groupLinkId));
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

    private static Long requireOwner(Account account) {
        if (account.getOwnerUserId() == null) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "历史无归属账号不能处理用户私有群健康事件");
        }
        return account.getOwnerUserId();
    }

    private static GroupLinkHealthReportedEvent toGroupEvent(
            ProtocolGroupHealthReportedEvent event) {
        return new GroupLinkHealthReportedEvent(
                event.tenantId(), event.groupLinkId(), event.groupJid(), event.health(),
                event.memberCount(), event.checkedAt(), event.errorCode(),
                event.protocolAccountId(), event.eventId());
    }

    /** 只有协议明确给出群封禁事实时才终止任务，避免把临时 403 误判为群封禁。 */
    private static boolean isExplicitGroupBan(String health, String errorCode) {
        if (!BANNED.equals(normalize(health))) {
            return false;
        }
        String normalizedErrorCode = normalize(errorCode);
        return CHAT_SUSPENDED.equals(normalizedErrorCode)
                || CHAT_TERMINATED.equals(normalizedErrorCode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
