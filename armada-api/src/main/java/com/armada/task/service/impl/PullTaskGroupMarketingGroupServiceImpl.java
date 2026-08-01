package com.armada.task.service.impl;

import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.task.mapper.PullTaskGroupMarketingCandidateMapper;
import com.armada.task.mapper.PullTaskGroupMarketingGroupOccupancyMapper;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolAddDTO;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolRemoveDTO;
import com.armada.task.model.entity.PullTaskGroupMarketingGroupOccupancy;
import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateAccountRow;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateAccountVO;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateVO;
import com.armada.task.model.vo.PullTaskGroupMarketingWaitingPoolRejectedVO;
import com.armada.task.model.vo.PullTaskGroupMarketingWaitingPoolVO;
import com.armada.task.service.PullTaskGroupMarketingCandidatePolicy;
import com.armada.task.service.PullTaskGroupMarketingGroupService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 拉群营销候选群组与等待池实现。 */
@Service
public class PullTaskGroupMarketingGroupServiceImpl
        implements PullTaskGroupMarketingGroupService {

    private static final int MAX_WAITING_GROUPS_PER_REQUEST = 500;
    private static final int MAX_TASK_NAME_LENGTH = 128;
    private static final long WAITING_LEASE_MILLIS = 2 * 60 * 60 * 1000L;
    private static final String WAITING_TYPE = "WAITING";
    private static final String INVALID_POOL_MESSAGE = "等待任务池不存在或无权访问";

    private final PullTaskGroupMarketingCandidateMapper candidateMapper;
    private final PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper;
    private final CountryService countryService;

    /**
     * 装配候选群组与等待池服务。
     *
     * @param candidateMapper 群组聚合只读 Mapper
     * @param occupancyMapper 单群占用 Mapper
     * @param countryService 国家主数据服务
     */
    public PullTaskGroupMarketingGroupServiceImpl(
            PullTaskGroupMarketingCandidateMapper candidateMapper,
            PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper,
            CountryService countryService) {
        this.candidateMapper = candidateMapper;
        this.occupancyMapper = occupancyMapper;
        this.countryService = countryService;
    }

    /**
     * 按群 JID 全局去重后分页读取候选群，并聚合同群全部有效管理账号。
     *
     * @param query 筛选与分页；为空时使用默认分页
     * @param operatorId 当前登录用户 ID
     * @return SQL 下推分页的候选群组
     */
    @Override
    @Transactional
    public PageResult<PullTaskGroupMarketingCandidateVO> listCandidates(
            PullTaskGroupMarketingCandidateQuery query,
            long operatorId) {
        long now = System.currentTimeMillis();
        occupancyMapper.releaseExpiredWaiting(now);
        PullTaskGroupMarketingCandidateQuery actual = query == null
                ? new PullTaskGroupMarketingCandidateQuery()
                : query;
        if (actual.getReservationToken() != null) {
            occupancyMapper.renewWaiting(
                    actual.getReservationToken(), operatorId, now + WAITING_LEASE_MILLIS, now);
        }
        long total = candidateMapper.countPage(actual);
        List<PullTaskGroupMarketingCandidateRow> rows = total == 0
                ? List.of()
                : candidateMapper.selectPage(actual, actual.getOffset(), actual.getPageSize());
        List<PullTaskGroupMarketingCandidateVO> items = assemble(
                rows, operatorId, actual.getReservationToken());
        return PageResult.of(items, actual.getPage(), actual.getPageSize(), total);
    }

    /**
     * 对选中群组重新读取本地事实并尝试取得软占用。
     *
     * <p>数据库有效占用唯一键解决并发点击；同一等待池重复提交幂等，
     * 其他池冲突逐群返回。</p>
     *
     * @param request 等待池标识、任务展示快照和群 JID
     * @param operatorId 当前登录用户 ID
     * @return 最新等待池和逐群拒绝原因
     */
    @Override
    @Transactional
    public PullTaskGroupMarketingWaitingPoolVO addWaiting(
            PullTaskGroupMarketingWaitingPoolAddDTO request,
            long operatorId) {
        long now = System.currentTimeMillis();
        occupancyMapper.releaseExpiredWaiting(now);
        List<String> groupJids = requireGroupJids(request);
        String token = reservationToken(request.reservationToken());
        requirePoolOwner(token, operatorId, true);
        occupancyMapper.renewWaiting(token, operatorId, now + WAITING_LEASE_MILLIS, now);
        String taskName = taskName(request.taskName());
        occupancyMapper.updateWaitingSnapshot(
                token, operatorId, taskName, request.plannedStartAt(), now);
        Map<String, PullTaskGroupMarketingCandidateRow> candidates = byJid(
                candidateMapper.selectByGroupJids(groupJids));
        List<PullTaskGroupMarketingWaitingPoolRejectedVO> rejected = new ArrayList<>();
        for (String groupJid : groupJids) {
            PullTaskGroupMarketingCandidateRow row = candidates.get(groupJid);
            if (row == null) {
                rejected.add(rejected(groupJid, "群组不存在、来源不明确或当前账号已不在群内"));
                continue;
            }
            PullTaskGroupMarketingCandidatePolicy.Decision decision =
                    PullTaskGroupMarketingCandidatePolicy.evaluate(row, operatorId, token);
            if (decision.inCurrentWaitingPool()) {
                continue;
            }
            if (!decision.selectable()) {
                rejected.add(rejected(groupJid, decision.disabledReason()));
                continue;
            }
            try {
                occupancyMapper.insertWaiting(occupancy(
                        row, token, taskName, request.plannedStartAt(), operatorId, now));
            } catch (DuplicateKeyException duplicate) {
                if (!ownedByCurrentPool(groupJid, token, operatorId)) {
                    rejected.add(rejected(groupJid, "群组刚被其他等待池或任务占用"));
                }
            }
        }
        return waitingPool(token, operatorId, rejected);
    }

    /**
     * 读取并续租当前用户等待池。
     *
     * @param reservationToken 等待池标识
     * @param operatorId 当前用户
     * @return 当前等待池
     */
    @Override
    @Transactional
    public PullTaskGroupMarketingWaitingPoolVO getWaiting(
            String reservationToken,
            long operatorId) {
        long now = System.currentTimeMillis();
        occupancyMapper.releaseExpiredWaiting(now);
        String token = requireReservationToken(reservationToken);
        requirePoolOwner(token, operatorId, false);
        occupancyMapper.renewWaiting(token, operatorId, now + WAITING_LEASE_MILLIS, now);
        return waitingPool(token, operatorId, List.of());
    }

    /**
     * 幂等释放当前用户等待池中的一个群。
     *
     * @param request 等待池和群 JID
     * @param operatorId 当前用户 ID
     * @return 释放后的等待池
     */
    @Override
    @Transactional
    public PullTaskGroupMarketingWaitingPoolVO removeWaiting(
            PullTaskGroupMarketingWaitingPoolRemoveDTO request,
            long operatorId) {
        long now = System.currentTimeMillis();
        occupancyMapper.releaseExpiredWaiting(now);
        String token = requireReservationToken(request == null ? null : request.reservationToken());
        String groupJid = requireGroupJid(request == null ? null : request.groupJid());
        requirePoolOwner(token, operatorId, false);
        occupancyMapper.releaseWaiting(token, groupJid, operatorId, now);
        occupancyMapper.renewWaiting(token, operatorId, now + WAITING_LEASE_MILLIS, now);
        return waitingPool(token, operatorId, List.of());
    }

    /**
     * 幂等释放当前用户整个等待池。
     *
     * @param reservationToken 等待池标识
     * @param operatorId 当前用户 ID
     */
    @Override
    @Transactional
    public void releaseWaiting(String reservationToken, long operatorId) {
        String token = requireReservationToken(reservationToken);
        long now = System.currentTimeMillis();
        occupancyMapper.releaseExpiredWaiting(now);
        requirePoolOwner(token, operatorId, true);
        occupancyMapper.releaseWaitingByToken(token, operatorId, now);
    }

    private PullTaskGroupMarketingWaitingPoolVO waitingPool(
            String token,
            long operatorId,
            List<PullTaskGroupMarketingWaitingPoolRejectedVO> rejected) {
        List<PullTaskGroupMarketingGroupOccupancy> occupancyRows =
                occupancyMapper.selectWaitingByToken(token, operatorId);
        List<String> groupJids = occupancyRows.stream()
                .map(PullTaskGroupMarketingGroupOccupancy::getGroupJid)
                .toList();
        Map<String, PullTaskGroupMarketingCandidateRow> candidates = groupJids.isEmpty()
                ? Map.of()
                : byJid(candidateMapper.selectByGroupJids(groupJids));
        List<PullTaskGroupMarketingCandidateRow> rows = occupancyRows.stream()
                .map(occupancy -> withOccupancy(candidates.get(occupancy.getGroupJid()), occupancy))
                .toList();
        return new PullTaskGroupMarketingWaitingPoolVO(
                token, assemble(rows, operatorId, token), rejected);
    }

    private List<PullTaskGroupMarketingCandidateVO> assemble(
            List<PullTaskGroupMarketingCandidateRow> rows,
            long operatorId,
            String token) {
        List<String> groupJids = rows.stream()
                .map(PullTaskGroupMarketingCandidateRow::getGroupJid)
                .toList();
        Map<String, List<PullTaskGroupMarketingCandidateAccountVO>> accounts =
                groupJids.isEmpty() ? Map.of() : accountsByJid(
                        candidateMapper.selectAccountsByGroupJids(groupJids));
        List<String> owners = rows.stream()
                .map(PullTaskGroupMarketingCandidateRow::getOwnerPhone)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, CountryReferenceVO> countries = owners.isEmpty()
                ? Map.of()
                : countryService.resolveActiveCountriesByPhoneNumbers(owners);
        return rows.stream()
                .map(row -> toVO(row, accounts.getOrDefault(row.getGroupJid(), List.of()),
                        countries.get(row.getOwnerPhone()), operatorId, token))
                .toList();
    }

    private static PullTaskGroupMarketingCandidateVO toVO(
            PullTaskGroupMarketingCandidateRow row,
            List<PullTaskGroupMarketingCandidateAccountVO> accounts,
            CountryReferenceVO country,
            long operatorId,
            String token) {
        PullTaskGroupMarketingCandidatePolicy.Decision decision =
                PullTaskGroupMarketingCandidatePolicy.evaluate(row, operatorId, token);
        return new PullTaskGroupMarketingCandidateVO(
                row.getGroupLinkId(), row.getGroupJid(), row.getGroupName(), source(row),
                row.getOwnerPhone(), country == null ? null : country.iso2(),
                country == null ? null : country.nameZh(), country == null ? null : country.flag(),
                row.getGroupCreatedAt(), row.getMemberSize(), row.getAnnounceOnly(), row.getAvatarUrl(),
                row.getLastSyncedAt(), row.getSourceJoinTaskId(), row.getSourceJoinTaskName(),
                row.getSourceJoinedAt(), row.getSourcePromotedAt(), accounts,
                zero(row.getEligibleAccountCount()), zero(row.getOnlineAccountCount()),
                decision.status(), decision.selectable(), decision.inCurrentWaitingPool(),
                row.getOccupiedTaskName(), decision.disabledReason(), row.getLastValidatedAt());
    }

    private static Map<String, List<PullTaskGroupMarketingCandidateAccountVO>> accountsByJid(
            List<PullTaskGroupMarketingCandidateAccountRow> rows) {
        Map<String, List<PullTaskGroupMarketingCandidateAccountVO>> result = new LinkedHashMap<>();
        for (PullTaskGroupMarketingCandidateAccountRow row : rows) {
            result.computeIfAbsent(row.getGroupJid(), ignored -> new ArrayList<>()).add(
                    new PullTaskGroupMarketingCandidateAccountVO(
                            row.getAccountId(), row.getAccountPhone(), row.getGroupRole(),
                            row.getLoginState(), row.getLastSeenAt()));
        }
        return result;
    }

    private static PullTaskGroupMarketingCandidateRow withOccupancy(
            PullTaskGroupMarketingCandidateRow candidate,
            PullTaskGroupMarketingGroupOccupancy occupancy) {
        PullTaskGroupMarketingCandidateRow row = candidate == null
                ? new PullTaskGroupMarketingCandidateRow()
                : candidate;
        row.setGroupLinkId(occupancy.getGroupLinkId());
        row.setGroupJid(occupancy.getGroupJid());
        PullTaskGroupSource groupSource = PullTaskGroupSource.valueOf(occupancy.getGroupSource());
        row.setHistorical(groupSource == PullTaskGroupSource.HISTORICAL);
        row.setSelfCollected(groupSource == PullTaskGroupSource.SELF_COLLECTED);
        row.setOccupancyType(occupancy.getOccupancyType());
        row.setReservationToken(occupancy.getReservationToken());
        row.setOccupiedTaskId(occupancy.getTaskId());
        row.setOccupiedTaskName(occupancy.getTaskNameSnapshot());
        row.setOccupiedBy(occupancy.getCreatedBy());
        row.setLastValidatedAt(occupancy.getLastValidatedAt());
        row.setLastValidationReason(occupancy.getLastValidationReason());
        return row;
    }

    private boolean ownedByCurrentPool(String groupJid, String token, long operatorId) {
        return occupancyMapper.selectActiveByGroupJids(List.of(groupJid)).stream()
                .anyMatch(row -> token.equals(row.getReservationToken())
                        && Long.valueOf(operatorId).equals(row.getCreatedBy()));
    }

    private void requirePoolOwner(String token, long operatorId, boolean missingAllowed) {
        Long owner = occupancyMapper.selectCreatorByToken(token);
        if (owner == null && missingAllowed) {
            return;
        }
        if (owner == null || owner.longValue() != operatorId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, INVALID_POOL_MESSAGE);
        }
    }

    private static PullTaskGroupMarketingGroupOccupancy occupancy(
            PullTaskGroupMarketingCandidateRow candidate,
            String token,
            String taskName,
            Long plannedStartAt,
            long operatorId,
            long now) {
        PullTaskGroupMarketingGroupOccupancy row = new PullTaskGroupMarketingGroupOccupancy();
        row.setGroupLinkId(candidate.getGroupLinkId());
        row.setGroupJid(candidate.getGroupJid());
        row.setGroupSource(source(candidate).name());
        row.setOccupancyType(WAITING_TYPE);
        row.setReservationToken(token);
        row.setTaskNameSnapshot(taskName);
        row.setPlannedStartAt(plannedStartAt);
        row.setLastValidatedAt(now);
        row.setCreatedBy(operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now + WAITING_LEASE_MILLIS);
        return row;
    }

    private static List<String> requireGroupJids(PullTaskGroupMarketingWaitingPoolAddDTO request) {
        Collection<String> values = request == null ? null : request.groupJids();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        if (normalized.isEmpty() || normalized.size() > MAX_WAITING_GROUPS_PER_REQUEST) {
            throw new BusinessException(ErrorCode.VALIDATION, "每次请选择1至500个有效群组");
        }
        return List.copyOf(normalized);
    }

    private static String reservationToken(String value) {
        return value == null || value.isBlank()
                ? UUID.randomUUID().toString()
                : requireReservationToken(value);
    }

    private static String requireReservationToken(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "等待任务池标识无效");
        }
    }

    private static String requireGroupJid(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组 JID 不能为空");
        }
        return value.trim();
    }

    private static String taskName(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > MAX_TASK_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能超过128个字符");
        }
        return normalized;
    }

    private static PullTaskGroupSource source(PullTaskGroupMarketingCandidateRow row) {
        if (Boolean.TRUE.equals(row.getHistorical())) {
            return PullTaskGroupSource.HISTORICAL;
        }
        if (Boolean.TRUE.equals(row.getSelfCollected())) {
            return PullTaskGroupSource.SELF_COLLECTED;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "群组来源已失效，请重新选择");
    }

    private static Map<String, PullTaskGroupMarketingCandidateRow> byJid(
            List<PullTaskGroupMarketingCandidateRow> rows) {
        Map<String, PullTaskGroupMarketingCandidateRow> result = new LinkedHashMap<>();
        for (PullTaskGroupMarketingCandidateRow row : rows) {
            result.put(row.getGroupJid(), row);
        }
        return result;
    }

    private static PullTaskGroupMarketingWaitingPoolRejectedVO rejected(
            String groupJid,
            String reason) {
        return new PullTaskGroupMarketingWaitingPoolRejectedVO(groupJid, reason);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}
