package com.armada.group.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.GroupLinkHealthCheckCandidate;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
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
        Map<Long, List<ProtocolGroupHealthCheckCommandRequest>> byTenant = groupByTenant(candidates);
        int enqueued = 0;
        Long previousTenant = TenantContext.get();
        try {
            for (Map.Entry<Long, List<ProtocolGroupHealthCheckCommandRequest>> entry : byTenant.entrySet()) {
                TenantContext.set(entry.getKey());
                ProtocolCommandOutboxEnqueueResult result =
                        outboxService.enqueueGroupHealthCheckCommands(entry.getValue());
                enqueued += result.inserted();
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
        log.info("group_link.health_check.enqueued scanned={} enqueued={} tenantBatches={}",
                candidates.size(), enqueued, byTenant.size());
        return new EnqueueResult(candidates.size(), enqueued, byTenant.size());
    }

    private static Map<Long, List<ProtocolGroupHealthCheckCommandRequest>> groupByTenant(
            List<GroupLinkHealthCheckCandidate> candidates) {
        Map<Long, List<ProtocolGroupHealthCheckCommandRequest>> byTenant = new LinkedHashMap<>();
        for (GroupLinkHealthCheckCandidate candidate : candidates) {
            byTenant.computeIfAbsent(candidate.tenantId(), ignored -> new ArrayList<>())
                    .add(new ProtocolGroupHealthCheckCommandRequest(
                            candidate.tenantId(),
                            candidate.groupLinkId(),
                            candidate.groupJid(),
                            candidate.accountId(),
                            candidate.protocolAccountId(),
                            SOURCE_SCHEDULED_GROUP_LINK_HEALTH));
        }
        return byTenant;
    }

    /** 本轮群链接健康检查命令入队摘要。 */
    public record EnqueueResult(int scanned, int enqueued, int tenantBatches) {
    }
}
