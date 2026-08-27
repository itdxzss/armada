package com.armada.group.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.vo.GroupLinkHealthCheckCandidate;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 群链接健康检查调度服务。
 *
 * <p>本服务只负责把“可检测候选”转换成协议层 Kafka outbox 命令。真实 WhatsApp
 * metadata 检测由协议层 owner worker 异步执行,结果再通过 {@code group.health_reported}
 * 事件回写健康表。</p>
 */
@Service
public class GroupLinkHealthCheckService {

    /** 定时巡检命令来源。 */
    public static final String SOURCE_SCHEDULED_GROUP_LINK_HEALTH = "scheduled_group_link_health";

    private static final Logger log = LoggerFactory.getLogger(GroupLinkHealthCheckService.class);

    private final GroupLinkMapper groupLinkMapper;
    private final ProtocolCommandOutboxService outboxService;

    /**
     * 创建群链接健康检查调度服务。
     *
     * @param groupLinkMapper 群链接 mapper
     * @param outboxService   协议命令 outbox service
     */
    public GroupLinkHealthCheckService(GroupLinkMapper groupLinkMapper,
                                       ProtocolCommandOutboxService outboxService) {
        this.groupLinkMapper = groupLinkMapper;
        this.outboxService = outboxService;
    }

    /**
     * 扫描候选群链接并写入协议层健康检查 outbox。
     *
     * <p>候选查询是跨租户的;写 outbox 时必须按租户分组并恢复 {@link TenantContext},
     * 让 outbox 表的 tenant_id 仍由租户拦截器注入,不手写跨租户 INSERT。</p>
     *
     * @param batchSize 本轮候选上限;小于等于 0 时直接跳过
     * @return 本轮扫描与入队摘要
     */
    public EnqueueResult enqueueDueHealthChecks(int batchSize) {
        if (batchSize <= 0) {
            return new EnqueueResult(0, 0, 0);
        }
        List<GroupLinkHealthCheckCandidate> candidates =
                groupLinkMapper.selectHealthCheckCandidates(batchSize, AccountLoginStateCode.ONLINE);
        Map<OwnerScopeKey, List<ProtocolGroupHealthCheckCommandRequest>> byOwnerScope =
                groupByOwnerScope(candidates);
        int enqueued = 0;
        Long previousTenant = TenantContext.get();
        try {
            for (Map.Entry<OwnerScopeKey, List<ProtocolGroupHealthCheckCommandRequest>> entry
                    : byOwnerScope.entrySet()) {
                OwnerScopeKey key = entry.getKey();
                TenantContext.set(key.tenantId());
                try (DataScopeContext.Scope ignored = DataScopeContext.open(
                        DataScope.self(key.ownerUserId()))) {
                    ProtocolCommandOutboxEnqueueResult result =
                            outboxService.enqueueGroupHealthCheckCommands(entry.getValue());
                    enqueued += result.inserted();
                }
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
        log.info("group_link.health_check.enqueued scanned={} enqueued={} tenantBatches={}",
                candidates.size(), enqueued, byOwnerScope.size());
        return new EnqueueResult(candidates.size(), enqueued, byOwnerScope.size());
    }

    private static Map<OwnerScopeKey, List<ProtocolGroupHealthCheckCommandRequest>> groupByOwnerScope(
            List<GroupLinkHealthCheckCandidate> candidates) {
        Map<OwnerScopeKey, List<ProtocolGroupHealthCheckCommandRequest>> byOwnerScope =
                new LinkedHashMap<>();
        for (GroupLinkHealthCheckCandidate candidate : candidates) {
            if (candidate.ownerUserId() == null) {
                log.error("群链接健康检查拒绝调度:链接缺少数据归属 tenantId={} groupLinkId={}",
                        candidate.tenantId(), candidate.groupLinkId());
                continue;
            }
            OwnerScopeKey key = new OwnerScopeKey(candidate.tenantId(), candidate.ownerUserId());
            byOwnerScope.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new ProtocolGroupHealthCheckCommandRequest(
                            candidate.tenantId(),
                            candidate.groupLinkId(),
                            candidate.groupJid(),
                            candidate.accountId(),
                            candidate.protocolAccountId(),
                            SOURCE_SCHEDULED_GROUP_LINK_HEALTH));
        }
        return byOwnerScope;
    }

    /** 本轮群链接健康检查命令入队摘要。 */
    public record EnqueueResult(int scanned, int enqueued, int tenantBatches) {
    }

    /** 后台巡检必须按租户和群链接 owner 分批恢复上下文。 */
    private record OwnerScopeKey(Long tenantId, Long ownerUserId) {
    }
}
