package com.armada.resource.service.impl;

import com.armada.resource.mapper.IpProxyBindTarget;
import com.armada.resource.mapper.IpProxyCandidateQuery;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.service.IpProxyAllocationRequest;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用普通候选查询和条件批量 UPDATE 抢占上线代理。
 *
 * <p>该类只负责代理竞争算法，不开启事务。调用方必须在 READ_COMMITTED 事务中先释放旧绑定，
 * 再调用本类；这样重试查询既能看到其它事务已提交的抢占，也能排除本事务已经置为 IN_USE 的代理。</p>
 */
final class IpProxyOptimisticAllocator {

    static final int CAS_BATCH_SIZE = 100;
    static final int MAX_CLAIM_ROUNDS = 3;

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
            for (int start = 0; start < group.size(); start += CAS_BATCH_SIZE) {
                int end = Math.min(start + CAS_BATCH_SIZE, group.size());
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
        for (int round = 1; round <= MAX_CLAIM_ROUNDS && !pending.isEmpty(); round++) {
            if (round > 1) {
                stats.retryRounds++;
            }
            List<ProxyClaim> claims = queryClaims(
                    tenantId, pending, excludedProxyIds, mixedRegion, stats);
            if (claims.isEmpty()) {
                throw insufficientProxy(chunk.size(), chunk.size() - pending.size());
            }
            List<IpProxyBindTarget> targets = claims.stream()
                    .map(claim -> new IpProxyBindTarget(claim.proxy().getId(), claim.request().accountId()))
                    .sorted(Comparator.comparing(IpProxyBindTarget::proxyId))
                    .toList();
            stats.casUpdatedRows += mapper.markUsingAndBindBatch(
                    targets,
                    IpProxyStatus.IDLE.code(),
                    IpProxyStatus.IN_USE.code(),
                    boundAt);
            stats.casStatements++;
            int verified = verifyClaims(claims, allocatedByAccountId);
            stats.conflicts += claims.size() - verified;
            // 本轮全部被并发请求抢占时也继续重查空闲池，让账号换一个候选代理。
            // 不把冲突 ID 追加进 NOT IN；READ_COMMITTED 下下一轮会自然看不到已提交的 IN_USE 行。
            Set<Long> completedAccountIds = new LinkedHashSet<>(allocatedByAccountId.keySet());
            pending.removeIf(request -> completedAccountIds.contains(request.accountId()));
        }
        if (!pending.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "代理分配冲突重试耗尽: pending=" + pending.size());
        }
    }

    private List<ProxyClaim> queryClaims(Long tenantId,
                                         List<IpProxyAllocationRequest> pending,
                                         List<Long> excludedProxyIds,
                                         String mixedRegion,
                                         AllocationStats stats) {
        IpProxyAllocationRequest first = pending.get(0);
        List<IpProxy> candidates = mapper.selectIdleByRegionPriority(new IpProxyCandidateQuery(
                tenantId,
                IpProxyStatus.IDLE.code(),
                first.preferredRegion(),
                mixedRegion,
                excludedProxyIds,
                first.allowOtherRegionFallback(),
                pending.size()));
        stats.candidateQueries++;
        int claimCount = Math.min(pending.size(), candidates.size());
        List<ProxyClaim> claims = new ArrayList<>(claimCount);
        for (int index = 0; index < claimCount; index++) {
            claims.add(new ProxyClaim(pending.get(index), candidates.get(index)));
        }
        return claims;
    }

    private int verifyClaims(List<ProxyClaim> claims, Map<Long, IpProxy> allocatedByAccountId) {
        List<Long> proxyIds = claims.stream()
                .map(claim -> claim.proxy().getId())
                .sorted()
                .toList();
        Map<Long, IpProxy> actualByProxyId = new LinkedHashMap<>();
        for (IpProxy actual : mapper.selectActiveByIds(proxyIds)) {
            actualByProxyId.put(actual.getId(), actual);
        }
        int verified = 0;
        for (ProxyClaim claim : claims) {
            IpProxy actual = actualByProxyId.get(claim.proxy().getId());
            if (actual != null
                    && Integer.valueOf(IpProxyStatus.IN_USE.code()).equals(actual.getStatus())
                    && claim.request().accountId().equals(actual.getBoundAccountId())) {
                allocatedByAccountId.put(claim.request().accountId(), actual);
                verified++;
            }
        }
        return verified;
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
     * @param casStatements CASE UPDATE 次数
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

    private record ProxyClaim(IpProxyAllocationRequest request, IpProxy proxy) {
    }

    private static final class AllocationStats {

        private int candidateQueries;
        private int casStatements;
        private int casUpdatedRows;
        private int conflicts;
        private int retryRounds;
    }
}
