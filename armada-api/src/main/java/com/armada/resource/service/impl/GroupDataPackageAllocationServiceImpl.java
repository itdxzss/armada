package com.armada.resource.service.impl;

import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.entity.GroupDataPackagePhone;
import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.service.GroupDataPackageAllocationService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 号码领取和最新任务结果投影，包头行锁串行化资源变更。 */
@Service
public class GroupDataPackageAllocationServiceImpl implements GroupDataPackageAllocationService {
    private static final int MAX_SNAPSHOT = 100_000;
    private static final int SQL_CHUNK = 300;
    private final GroupDataPackageMapper packages;
    private final GroupDataPackagePhoneMapper phones;
    private final GroupDataPackageStatMapper stats;

    /** 注入资源域实际数据访问。 */
    public GroupDataPackageAllocationServiceImpl(GroupDataPackageMapper packages,
            GroupDataPackagePhoneMapper phones, GroupDataPackageStatMapper stats) {
        this.packages = packages;
        this.phones = phones;
        this.stats = stats;
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot(long packageId, int limit) {
        if (limit < 1 || limit > MAX_SNAPSHOT) { throw invalid("选择号码数量须为1至100000"); }
        GroupDataPackage parent = packages.selectActive(packageId);
        if (parent == null) { throw missing(); }
        List<GroupDataPackagePhone> selected = phones.snapshot(packageId, parent.getGeneration(), limit + 1);
        if (selected.size() > limit) { throw invalid("当前可用号码超过" + limit + "，请拆分数据包后再选择"); }
        return new Snapshot(packageId, parent.getName(), parent.getGeneration(), selected.stream()
                .map(GroupDataPackageAllocationServiceImpl::toPhone).toList());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Phone> claim(ClaimRequest request) {
        validateClaim(request);
        GroupDataPackage parent = packages.lockActive(request.packageId());
        if (parent == null) { throw missing(); }
        if (parent.getGeneration() != request.generation()) { throw conflict(); }
        List<GroupDataPackagePhone> rows = readPhones(request.phoneIds(), true);
        if (rows.size() != request.phoneIds().size()) { throw conflict(); }
        long now = System.currentTimeMillis();
        List<Long> changedIds = new ArrayList<>();
        for (GroupDataPackagePhone row : rows) {
            if (!Objects.equals(row.getPackageId(), request.packageId())
                    || row.getGeneration() != request.generation()) { throw conflict(); }
            if (sameClaim(row, request)) { continue; }
            if (row.getStatus() != GroupDataPackagePhoneStatus.UNUSED.code()) { throw conflict(); }
            row.setClaimedTaskId(request.taskId());
            row.setClaimedExecutionSeq(request.executionSeq());
            row.setUpdatedAt(now);
            row.setAllocationVersion(row.getAllocationVersion() + 1);
            changedIds.add(row.getId());
        }
        for (int start = 0; start < changedIds.size(); start += SQL_CHUNK) {
            List<Long> chunk = changedIds.subList(start, Math.min(start + SQL_CHUNK, changedIds.size()));
            if (phones.claimBatch(chunk, request, now) != chunk.size()) { throw conflict(); }
        }
        if (!changedIds.isEmpty()) {
            move(parent.getId(), parent.getGeneration(), GroupDataPackagePhoneStatus.UNUSED.code(),
                    GroupDataPackagePhoneStatus.CLAIMED.code(), changedIds.size());
            packages.markUsed(parent.getId(), now);
        }
        return rows.stream().map(GroupDataPackageAllocationServiceImpl::toPhone).toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(List<AllocationRef> allocations) {
        if (allocations == null || allocations.isEmpty()) { return; }
        apply(allocations.stream().map(ref -> new Settlement(ref, GroupDataPackagePhoneStatus.UNUSED)).toList(), true);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settle(List<Settlement> settlements) {
        if (settlements == null || settlements.isEmpty()) { return; }
        if (settlements.stream().anyMatch(s -> s.status() == null || s.status() == GroupDataPackagePhoneStatus.UNUSED)) {
            throw invalid("结果投影不能把号码直接回收到未用");
        }
        apply(settlements, false);
    }

    private void apply(List<Settlement> settlements, boolean releasing) {
        List<Long> ids = settlements.stream().map(s -> s.allocation().phoneId()).distinct().toList();
        List<GroupDataPackagePhone> before = readPhones(ids, false);
        Map<Long, GroupDataPackage> parents = new LinkedHashMap<>();
        before.stream().map(GroupDataPackagePhone::getPackageId).distinct().sorted()
                .forEach(id -> parents.put(id, packages.lockActive(id)));
        Map<Long, GroupDataPackagePhone> byId = readPhones(ids, true).stream()
                .collect(Collectors.toMap(GroupDataPackagePhone::getId, Function.identity()));
        Map<Movement, List<GroupDataPackagePhone>> movements = new LinkedHashMap<>();
        for (Settlement settlement : settlements) {
            GroupDataPackagePhone row = byId.get(settlement.allocation().phoneId());
            if (!matches(row, settlement.allocation()) || row.getStatus() == settlement.status().code()) { continue; }
            if (releasing && row.getStatus() != GroupDataPackagePhoneStatus.CLAIMED.code()) { continue; }
            byId.remove(row.getId());
            Movement key = new Movement(row.getPackageId(), row.getGeneration(), row.getStatus(), settlement.status().code());
            movements.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        long now = System.currentTimeMillis();
        for (var entry : movements.entrySet()) {
            Movement key = entry.getKey();
            List<GroupDataPackagePhone> rows = entry.getValue();
            for (int start = 0; start < rows.size(); start += SQL_CHUNK) {
                List<GroupDataPackagePhone> chunk = rows.subList(start, Math.min(start + SQL_CHUNK, rows.size()));
                if (phones.transitionBatch(chunk, key.from(), key.to(), now) != chunk.size()) { throw conflict(); }
            }
            // 旧代事实保留，但当前代统计不受影响。
            int updated = stats.move(key.packageId(), key.generation(), key.from(), key.to(), rows.size());
            if (updated == 0) {
                var current = parents.get(key.packageId());
                if (current == null || current.getGeneration() == key.generation()) { throw conflict(); }
            }
        }
    }

    private List<GroupDataPackagePhone> readPhones(List<Long> ids, boolean currentRead) {
        List<GroupDataPackagePhone> rows = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += SQL_CHUNK) {
            List<Long> chunk = ids.subList(start, Math.min(start + SQL_CHUNK, ids.size()));
            rows.addAll(currentRead ? phones.lockByIds(chunk) : phones.byIds(chunk));
        }
        rows.sort(java.util.Comparator.comparingInt(GroupDataPackagePhone::getMemberSeq));
        return rows;
    }

    private static boolean matches(GroupDataPackagePhone row, AllocationRef reference) {
        return row != null && Objects.equals(row.getClaimedTaskId(), reference.taskId())
                && Objects.equals(row.getClaimedExecutionSeq(), reference.executionSeq())
                && Objects.equals(row.getAllocationVersion(), reference.allocationVersion());
    }

    private record Movement(long packageId, int generation, int from, int to) { }

    private void move(long id, int generation, int from, int to, int count) {
        if (stats.move(id, generation, from, to, count) != 1) { throw conflict(); }
    }

    private static void validateClaim(ClaimRequest request) {
        if (request == null || request.packageId() < 1 || request.taskId() < 1
                || request.executionSeq() < 1 || request.generation() < 1
                || request.phoneIds() == null || request.phoneIds().isEmpty()
                || request.phoneIds().size() > MAX_SNAPSHOT) { throw invalid("领取请求不合法"); }
        if (request.phoneIds().stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(request.phoneIds()).size() != request.phoneIds().size()) {
            throw invalid("领取号码ID重复或不合法");
        }
    }

    private static boolean sameClaim(GroupDataPackagePhone row, ClaimRequest request) {
        return Objects.equals(row.getClaimedTaskId(), request.taskId())
                && Objects.equals(row.getClaimedExecutionSeq(), request.executionSeq());
    }

    private static Phone toPhone(GroupDataPackagePhone row) {
        return new Phone(row.getId(), row.getPhone(), Boolean.TRUE.equals(row.getAdminRequired()),
                row.getMemberSeq(), row.getSourceLineNo(), row.getCountryIso2(), row.getAllocationVersion());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "数据包号码已被使用或版本已变化，请刷新计划");
    }
    private static BusinessException missing() {
        return new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除");
    }
}
