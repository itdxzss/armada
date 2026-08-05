package com.armada.group.scheduler;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupClassificationBackfillCandidate;
import com.armada.shared.tenant.TenantContext;
import com.armada.group.service.GroupMetadataSyncTaskService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 无远程调用、分批幂等的历史群与上控后群存量分类回填。 */
@Component
@EnableConfigurationProperties(GroupClassificationBackfillProperties.class)
public class GroupClassificationBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(GroupClassificationBackfillJob.class);
    private static final int MAX_BATCH_SIZE = 500;
    private static final String ACCOUNT_SYNC_LINK_PREFIX = "wa://group/";
    private static final int GROUP_NAME_MAX_LENGTH = 128;

    private final GroupLinkMapper mapper;
    private final GroupClassificationBackfillProperties properties;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建分类回填 job。
     *
     * @param mapper 群入口数据访问
     * @param properties 回填配置
     * @param metadataSyncTaskService 群详情同步任务服务
     */
    public GroupClassificationBackfillJob(
            GroupLinkMapper mapper,
            GroupClassificationBackfillProperties properties,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.mapper = mapper;
        this.properties = properties;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    /**
     * 执行一轮分类回填。
     *
     * @return 本轮历史候选数和上控后候选数
     */
    @Scheduled(fixedDelayString = "${armada.group-classification-backfill.fixed-delay-ms:30000}")
    public BackfillResult backfillOnce() {
        if (!properties.enabled()) {
            return new BackfillResult(0, 0);
        }
        int limit = Math.max(1, Math.min(properties.batchSize(), MAX_BATCH_SIZE));
        long now = System.currentTimeMillis();
        List<GroupClassificationBackfillCandidate> historical =
                mapper.selectHistoricalClassificationBackfillCandidates(limit);
        for (GroupClassificationBackfillCandidate candidate : historical) {
            withTenant(candidate.tenantId(), () -> backfillHistorical(candidate, now));
        }
        List<GroupClassificationBackfillCandidate> postControl =
                mapper.selectPostControlClassificationBackfillCandidates(limit);
        for (GroupClassificationBackfillCandidate candidate : postControl) {
            withTenant(candidate.tenantId(), () -> {
                if (mapper.markPostControl(candidate.groupLinkId(), now) == 1) {
                    metadataSyncTaskService.enqueue(
                            candidate.groupLinkId(), GroupMetadataSyncTrigger.BACKFILL, now);
                }
            });
        }
        if (!historical.isEmpty() || !postControl.isEmpty()) {
            log.info("群分类存量回填完成 historical={} postControl={}",
                    historical.size(), postControl.size());
        }
        return new BackfillResult(historical.size(), postControl.size());
    }

    private void backfillHistorical(GroupClassificationBackfillCandidate candidate, long now) {
        if (candidate.groupLinkId() != null) {
            markHistorical(candidate.groupLinkId(), candidate.deletedAt(), now);
            return;
        }
        GroupLink row = new GroupLink();
        row.setLinkUrl(ACCOUNT_SYNC_LINK_PREFIX + candidate.groupJid());
        row.setGroupName(clamp(candidate.groupName(), GROUP_NAME_MAX_LENGTH));
        row.setOrigin(GroupLinkOrigin.ACCOUNT_SYNC.code());
        row.setMembershipState(GroupMembershipState.JOINED.code());
        row.setIsHistorical(true);
        row.setIsPostControl(false);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        int inserted = mapper.insertHistoricalBaselineGroupIgnore(row);
        GroupLink stored = mapper.selectAnyByUrl(row.getLinkUrl());
        if (stored != null) {
            boolean newlyClassified = markHistorical(stored.getId(), stored.getDeletedAt(), now);
            if (stored.getDeletedAt() == null && (inserted == 1 || newlyClassified)) {
                metadataSyncTaskService.enqueue(
                        stored.getId(), GroupMetadataSyncTrigger.BACKFILL, now);
            }
        }
    }

    private boolean markHistorical(Long groupLinkId, Long deletedAt, long now) {
        if (deletedAt == null) {
            return mapper.markHistorical(groupLinkId, now) == 1;
        }
        mapper.markHistoricalIncludingDeleted(groupLinkId, now);
        return false;
    }

    private static void withTenant(Long tenantId, Runnable action) {
        if (tenantId == null) {
            return;
        }
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            action.run();
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    /** 一轮分类回填结果。 */
    public record BackfillResult(int historicalCandidates, int postControlCandidates) {
    }
}
