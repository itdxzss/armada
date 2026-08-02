package com.armada.account.service;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.account.model.vo.AccountGroupSyncCandidate;
import com.armada.account.model.vo.AccountGroupTargetSyncRequest;
import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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

    /** 营销任务导出前的按需群成员刷新来源。 */
    public static final String SOURCE_MARKETING_TASK_EXPORT_GROUP_SYNC =
            "marketing_task_export_group_sync";

    private static final int COMMAND_BATCH_SIZE = 500;

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
        Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant =
                groupByTenant(candidates, SOURCE_SCHEDULED_ACCOUNT_GROUP_SYNC, Map.of());
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

    /**
     * 为营销任务导出定向刷新任务实际涉及的 WhatsApp 群完整成员快照。
     *
     * <p>账号只承担观察入口；导出范围仍由营销任务的目标群 JID 决定。该入口不更新后台轮转水位，
     * 避免一次人工导出改变定时巡检顺序。</p>
     *
     * @param targets 任务群 JID 及可访问该群的协议账号
     * @return 扫描与入队摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public EnqueueResult enqueueMarketingExportSyncCommands(List<AccountGroupTargetSyncRequest> targets) {
        Map<Long, List<String>> groupJidsByAccount = normalizeTargetGroups(targets);
        if (groupJidsByAccount.isEmpty()) {
            return new EnqueueResult(0, 0, 0);
        }
        List<Long> normalizedIds = new ArrayList<>(groupJidsByAccount.keySet());
        List<AccountGroupSyncCandidate> candidates = new ArrayList<>();
        for (int start = 0; start < normalizedIds.size(); start += COMMAND_BATCH_SIZE) {
            int end = Math.min(start + COMMAND_BATCH_SIZE, normalizedIds.size());
            candidates.addAll(accountMapper.selectGroupSyncCandidatesByIds(
                    normalizedIds.subList(start, end)));
        }
        Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant =
                groupByTenant(candidates, SOURCE_MARKETING_TASK_EXPORT_GROUP_SYNC, groupJidsByAccount);
        int enqueued = enqueueByTenant(byTenant);
        log.info("marketing_export.group_sync.enqueued scanned={} enqueued={} tenantBatches={}",
                candidates.size(), enqueued, byTenant.size());
        return new EnqueueResult(candidates.size(), enqueued, byTenant.size());
    }

    private static Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> groupByTenant(
            List<AccountGroupSyncCandidate> candidates,
            String source,
            Map<Long, List<String>> targetedGroupJids) {
        Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant = new LinkedHashMap<>();
        for (AccountGroupSyncCandidate candidate : candidates) {
            List<String> groupJids = targetedGroupJids.isEmpty()
                    ? List.of()
                    : targetedGroupJids.get(candidate.accountId());
            if (!targetedGroupJids.isEmpty() && (groupJids == null || groupJids.isEmpty())) {
                continue;
            }
            List<List<String>> groupJidBatches;
            if (groupJids.isEmpty()) {
                groupJidBatches = List.of(List.of());
            } else if (candidate.protocolBackend() == ProtocolBackend.ANDROID) {
                groupJidBatches = partition(groupJids, COMMAND_BATCH_SIZE);
            } else {
                // Web master 目前忽略 groupJids 并执行一次全账号群同步，不能因分片重复全量查询。
                groupJidBatches = List.of(groupJids);
            }
            List<ProtocolAccountGroupSyncCommandRequest> tenantCommands =
                    byTenant.computeIfAbsent(candidate.tenantId(), ignored -> new ArrayList<>());
            for (List<String> groupJidBatch : groupJidBatches) {
                tenantCommands.add(new ProtocolAccountGroupSyncCommandRequest(
                            candidate.tenantId(),
                            candidate.accountId(),
                            candidate.protocolAccountId(),
                            candidate.protocolBackend(),
                            candidate.phone(),
                            groupJidBatch,
                            source));
            }
        }
        return byTenant;
    }

    private static <T> List<List<T>> partition(List<T> values, int size) {
        List<List<T>> partitions = new ArrayList<>((values.size() + size - 1) / size);
        for (int start = 0; start < values.size(); start += size) {
            partitions.add(List.copyOf(values.subList(start, Math.min(start + size, values.size()))));
        }
        return partitions;
    }

    private int enqueueByTenant(Map<Long, List<ProtocolAccountGroupSyncCommandRequest>> byTenant) {
        int enqueued = 0;
        Long previousTenant = TenantContext.get();
        try {
            for (Map.Entry<Long, List<ProtocolAccountGroupSyncCommandRequest>> entry : byTenant.entrySet()) {
                TenantContext.set(entry.getKey());
                List<ProtocolAccountGroupSyncCommandRequest> commands = entry.getValue();
                for (int start = 0; start < commands.size(); start += COMMAND_BATCH_SIZE) {
                    int end = Math.min(start + COMMAND_BATCH_SIZE, commands.size());
                    enqueued += outboxService.enqueueAccountGroupSyncCommands(
                            commands.subList(start, end)).inserted();
                }
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
        return enqueued;
    }

    private static Map<Long, List<String>> normalizeTargetGroups(
            List<AccountGroupTargetSyncRequest> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> grouped = new java.util.TreeMap<>();
        for (AccountGroupTargetSyncRequest target : targets) {
            if (target == null || target.accountId() == null || target.accountId() <= 0) {
                continue;
            }
            String groupJid = Objects.toString(target.groupJid(), "").trim();
            if (groupJid.isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(target.accountId(), ignored -> new TreeSet<>()).add(groupJid);
        }
        Map<Long, List<String>> normalized = new LinkedHashMap<>();
        grouped.forEach((accountId, groupJids) -> normalized.put(accountId, List.copyOf(groupJids)));
        return normalized;
    }

    private void markRequested(List<ProtocolAccountGroupSyncCommandRequest> commands) {
        long requestedAt = System.currentTimeMillis();
        List<Long> accountIds = accountIds(commands);
        int updated = accountMapper.markGroupSyncRequested(accountIds, requestedAt);
        if (updated < accountIds.size()) {
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
