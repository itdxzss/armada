package com.armada.account.service;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.account.model.vo.AccountGroupSyncCandidate;
import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号当前群同步调度服务。
 *
 * <p>本服务只负责把“可同步账号”转换成协议层 Kafka outbox 命令。真实
 * listParticipating 由协议层 owner worker 异步执行,结果再通过
 * {@code account.groups_reported} 事件回写账号群关系表。</p>
 */
@Service
public class AccountGroupSyncCommandService {

    /** 定时账号群同步命令来源。 */
    public static final String SOURCE_SCHEDULED_ACCOUNT_GROUP_SYNC = "scheduled_account_group_sync";

    private static final Logger log = LoggerFactory.getLogger(AccountGroupSyncCommandService.class);

    private final AccountMapper accountMapper;
    private final ProtocolCommandOutboxService outboxService;

    /**
     * 创建账号群同步调度服务。
     *
     * @param accountMapper 账号 mapper
     * @param outboxService 协议命令 outbox service
     */
    public AccountGroupSyncCommandService(AccountMapper accountMapper,
                                          ProtocolCommandOutboxService outboxService) {
        this.accountMapper = accountMapper;
        this.outboxService = outboxService;
    }

    /**
     * 扫描候选账号并写入协议层账号群同步 outbox。
     *
     * <p>候选查询是跨租户的;写 outbox 时必须按租户分组并恢复 {@link TenantContext},
     * 让 outbox 表的 tenant_id 仍由租户拦截器注入,不手写跨租户 INSERT。</p>
     *
     * @param batchSize 本轮候选上限;小于等于 0 时直接跳过
     * @return 本轮扫描与入队摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public EnqueueResult enqueueDueSyncCommands(int batchSize) {
        if (batchSize <= 0) {
            return new EnqueueResult(0, 0, 0);
        }
        List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
                batchSize,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                AccountGroupBaselineStateCode.CAPTURED);
        Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant = groupByTenant(candidates);
        int enqueued = 0;
        Long previousTenant = TenantContext.get();
        try {
            for (Map.Entry<Long, List<ProtocolAccountGroupSyncCommandRequest>> entry : byTenant.entrySet()) {
                TenantContext.set(entry.getKey());
                ProtocolCommandOutboxEnqueueResult result =
                        outboxService.enqueueAccountGroupSyncCommands(entry.getValue());
                markRequested(entry.getValue());
                enqueued += result.inserted();
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
        log.info("account_group.sync.enqueued scanned={} enqueued={} tenantBatches={}",
                candidates.size(), enqueued, byTenant.size());
        return new EnqueueResult(candidates.size(), enqueued, byTenant.size());
    }

    private static Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> groupByTenant(
            List<AccountGroupSyncCandidate> candidates) {
        Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant = new LinkedHashMap<>();
        for (AccountGroupSyncCandidate candidate : candidates) {
            byTenant.computeIfAbsent(candidate.tenantId(), ignored -> new ArrayList<>())
                    .add(new ProtocolAccountGroupSyncCommandRequest(
                            candidate.tenantId(),
                            candidate.accountId(),
                            candidate.protocolAccountId(),
                            SOURCE_SCHEDULED_ACCOUNT_GROUP_SYNC));
        }
        return byTenant;
    }

    private void markRequested(List<ProtocolAccountGroupSyncCommandRequest> commands) {
        long requestedAt = System.currentTimeMillis();
        List<Long> accountIds = accountIds(commands);
        int updated = accountMapper.markGroupSyncRequested(accountIds, requestedAt);
        if (updated != accountIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "账号群同步水位更新数量不一致: expected=" + accountIds.size() + ", updated=" + updated);
        }
    }

    private static List<Long> accountIds(List<ProtocolAccountGroupSyncCommandRequest> commands) {
        List<Long> accountIds = new ArrayList<>(commands.size());
        for (ProtocolAccountGroupSyncCommandRequest command : commands) {
            accountIds.add(command.accountId());
        }
        return accountIds;
    }

    /** 本轮账号群同步命令入队摘要。 */
    public record EnqueueResult(int scanned, int enqueued, int tenantBatches) {
    }
}
