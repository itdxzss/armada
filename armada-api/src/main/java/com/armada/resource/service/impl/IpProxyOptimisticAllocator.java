package com.armada.resource.service.impl;

import com.armada.resource.mapper.IpProxyCandidateQuery;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.service.IpProxyAllocationRequest;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用普通候选查询和单行条件 UPDATE 抢占上线代理。
 *
 * <p>该类只负责代理竞争算法，不开启事务。候选 SELECT 允许读到并发旧快照，真正归属以
 * {@code UPDATE ... WHERE status=IDLE} 返回行数为准；抢占失败后直接尝试下一候选，
 * 因此不依赖调用方修改默认事务隔离级别。</p>
 */
final class IpProxyOptimisticAllocator {

    static final int CANDIDATE_BATCH_SIZE = 100;

    private final IpProxyMapper mapper;

    IpProxyOptimisticAllocator(IpProxyMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按国家策略稳定分组并为全部账号抢占代理。
     *
     * @param tenantId 当前租户 ID
     * @param requests 已归一化且账号不重复的分配请求
     * @param excludedProxyIds 删除代理重登等固定排除集合
     * @param boundAt 统一绑定时间
     * @param mixedRegion 混合池展示值
     * @return 按原请求顺序排列的代理与竞争统计
     * @throws BusinessException 候选不足、连续冲突无进展或三轮后仍未全部分配时抛出
     */
    Result allocate(Long tenantId,
                    List<IpProxyAllocationRequest> requests,
                    List<Long> excludedProxyIds,
                    long boundAt,
                    String mixedRegion) {
        Map<AllocationStrategy, List<IpProxyAllocationRequest>> groups = groupRequests(requests);
        Map<Long, IpProxy> allocatedByAccountId = new LinkedHashMap<>();
        AllocationStats stats = new AllocationStats();
        for (List<IpProxyAllocationRequest> group : groups.values()) {
            for (int start = 0; start < group.size(); start += CANDIDATE_BATCH_SIZE) {
                int end = Math.min(start + CANDIDATE_BATCH_SIZE, group.size());
                claimChunk(tenantId, group.subList(start, end), excludedProxyIds,
                        boundAt, mixedRegion, allocatedByAccountId, stats);
            }
        }
        List<IpProxy> ordered = requests.stream()
                .map(request -> allocatedByAccountId.get(request.accountId()))
                .toList();
        return new Result(
                ordered,
                stats.candidateQueries,
                stats.casStatements,
                stats.casUpdatedRows,
                stats.conflicts,
                stats.retryRounds);
    }

    private Map<AllocationStrategy, List<IpProxyAllocationRequest>> groupRequests(
            List<IpProxyAllocationRequest> requests) {
        Map<AllocationStrategy, List<IpProxyAllocationRequest>> groups = new LinkedHashMap<>();
        for (IpProxyAllocationRequest request : requests) {
            AllocationStrategy strategy = new AllocationStrategy(
                    request.preferredRegion(), request.allowOtherRegionFallback());
            groups.computeIfAbsent(strategy, ignored -> new ArrayList<>()).add(request);
        }
        return groups;
    }

    private void claimChunk(Long tenantId,
                            List<IpProxyAllocationRequest> chunk,
                            List<Long> excludedProxyIds,
                            long boundAt,
                            String mixedRegion,
                            Map<Long, IpProxy> allocatedByAccountId,
                            AllocationStats stats) {
        List<IpProxyAllocationRequest> pending = new ArrayList<>(chunk);
        Set<Long> attemptedProxyIds = new LinkedHashSet<>();
        int candidateRound = 0;
        while (!pending.isEmpty()) {
            List<IpProxy> candidates = queryCandidates(
                    tenantId, pending.get(0), excludedProxyIds, attemptedProxyIds, mixedRegion, stats);
            if (candidates.isEmpty()) {
                throw insufficientProxy(chunk.size(), chunk.size() - pending.size());
            }
            if (candidateRound++ > 0) {
                stats.retryRounds++;
            }
            claimCandidates(pending, candidates, attemptedProxyIds, boundAt, allocatedByAccountId, stats);
        }
    }

    private List<IpProxy> queryCandidates(Long tenantId,
                                          IpProxyAllocationRequest request,
                                          List<Long> excludedProxyIds,
                                          Set<Long> attemptedProxyIds,
                                          String mixedRegion,
                                          AllocationStats stats) {
        Set<Long> queryExclusions = new LinkedHashSet<>(excludedProxyIds);
        queryExclusions.addAll(attemptedProxyIds);
        List<IpProxy> candidates = mapper.selectIdleByRegionPriority(new IpProxyCandidateQuery(
                tenantId,
                IpProxyStatus.IDLE.code(),
                request.preferredRegion(),
                mixedRegion,
                List.copyOf(queryExclusions),
                request.allowOtherRegionFallback(),
                CANDIDATE_BATCH_SIZE));
        stats.candidateQueries++;
        return candidates.stream()
                .filter(candidate -> candidate.getId() != null)
                .filter(candidate -> !attemptedProxyIds.contains(candidate.getId()))
                .toList();
    }

    private void claimCandidates(List<IpProxyAllocationRequest> pending,
                                 List<IpProxy> candidates,
                                 Set<Long> attemptedProxyIds,
                                 long boundAt,
                                 Map<Long, IpProxy> allocatedByAccountId,
                                 AllocationStats stats) {
        for (IpProxy candidate : candidates) {
            if (pending.isEmpty()) {
                return;
            }
            attemptedProxyIds.add(candidate.getId());
            IpProxyAllocationRequest request = pending.get(0);
            int updated = mapper.markUsingAndBind(
                    candidate.getId(),
                    request.accountId(),
                    IpProxyStatus.IDLE.code(),
                    IpProxyStatus.IN_USE.code(),
                    boundAt);
            stats.casStatements++;
            stats.casUpdatedRows += updated;
            if (updated == 1) {
                candidate.setStatus(IpProxyStatus.IN_USE.code());
                candidate.setBoundAccountId(request.accountId());
                candidate.setBoundAt(boundAt);
                allocatedByAccountId.put(request.accountId(), candidate);
                pending.remove(0);
            } else {
                stats.conflicts++;
            }
        }
    }

    private BusinessException insufficientProxy(int requested, int allocated) {
        if (requested == 1) {
            return new BusinessException(ErrorCode.VALIDATION, "暂无空闲代理");
        }
        return new BusinessException(
                ErrorCode.VALIDATION,
                "暂无足够空闲代理: requested=" + requested + " allocated=" + allocated);
    }

    /**
     * 已完成分配及批次竞争统计。
     *
     * @param proxies 按请求顺序排列的代理
     * @param candidateQueries 候选查询次数
     * @param casStatements 单行条件 UPDATE 次数
     * @param casUpdatedRows JDBC 报告的更新总行数
     * @param conflicts UPDATE 后映射不符合预期的数量
     * @param retryRounds 首轮之后的重试轮数
     */
    record Result(
            List<IpProxy> proxies,
            int candidateQueries,
            int casStatements,
            int casUpdatedRows,
            int conflicts,
            int retryRounds
    ) {
    }

    private record AllocationStrategy(String preferredRegion, boolean allowOtherRegionFallback) {
    }

    private static final class AllocationStats {

        private int candidateQueries;
        private int casStatements;
        private int casUpdatedRows;
        private int conflicts;
        private int retryRounds;
    }
}
