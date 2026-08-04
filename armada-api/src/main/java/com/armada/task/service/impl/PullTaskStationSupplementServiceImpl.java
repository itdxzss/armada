package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskResourceSupplementTransition;
import com.armada.task.model.dto.PullTaskStationSupplementDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskStationCandidateVO;
import com.armada.task.model.vo.PullTaskStationSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskStationCandidates;
import com.armada.task.service.PullTaskStationSupplementService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 补充站台候选过滤、不可变锁定和拉人检查点恢复实现。 */
@Service
public class PullTaskStationSupplementServiceImpl implements PullTaskStationSupplementService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final List<Integer> SUPPLEMENTABLE_STATUSES =
            List.of(PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final List<Integer> SUPPLEMENTABLE_STAGES =
            List.of(PullTaskExecutionStage.PULL_EXECUTION.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskStationSupplementResources resources;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;

    public PullTaskStationSupplementServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskStationSupplementResources resources,
            PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.resources = resources;
        this.dispatchTrigger = dispatchTrigger;
    }

    @Override
    @Transactional(readOnly = true)
    public PullTaskStationSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId) {
        Context context = context(taskId, executionId);
        Long groupId = accountGroupId == null
                ? context.setting().getStationGroupId() : accountGroupId;
        requireGroup(groupId);
        PullTaskStationCandidates current = resources.stationSelectionService()
                .findCandidates(context.execution(), context.setting());
        return new PullTaskStationSupplementOptionsVO(
                requiredCount(context.setting()), current.missingCount(), groupId,
                candidates(groupId, context.execution(), current));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supplement(
            long taskId, long executionId, PullTaskStationSupplementDTO request) {
        PullTaskSelectionMode selectionMode = validateRequest(request);
        Context context = context(taskId, executionId);
        requireStationWait(context.execution());
        requireGroup(request.accountGroupId());
        PullTaskStationCandidates current = resources.stationSelectionService()
                .findCandidates(context.execution(), context.setting());
        if (request.supplementCount() > current.missingCount()) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充数量超过当前站台缺口");
        }
        List<PullTaskStationCandidateVO> candidates =
                candidates(request.accountGroupId(), context.execution(), current);
        List<PullTaskStationCandidateVO> selected =
                select(request, selectionMode, candidates);
        long now = System.currentTimeMillis();
        insertSelections(context.execution(), request, selectionMode, selected, now);
        activate(context.execution(), now);
        dispatchTrigger.dispatchAfterCommit();
    }

    private Context context(long taskId, long executionId) {
        PullTask task = taskMapper.selectLifecycle(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务不存在");
        }
        if (task.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(task.getMode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前任务不是普通群链接任务");
        }
        if (!supplementableParent(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前任务状态不允许补充站台");
        }
        PullTaskGroupExecution execution = resources.executionMapper().selectById(executionId);
        if (execution == null || !Objects.equals(execution.getTaskId(), taskId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群执行行不存在");
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        if (setting == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "普通拉群冻结配置不存在");
        }
        return new Context(execution, setting);
    }

    private PullTaskSelectionMode validateRequest(PullTaskStationSupplementDTO request) {
        if (request == null || request.accountGroupId() == null
                || request.supplementCount() == null || request.supplementCount() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "站台分组和补充数量不能为空");
        }
        PullTaskSelectionMode mode = PullTaskSelectionMode.fromCode(request.selectionMode());
        if (mode == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "站台选择方式无效");
        }
        Set<Long> distinct = new HashSet<>(request.accountIds());
        if (mode == PullTaskSelectionMode.AUTOMATIC && !distinct.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "自动选择不应传入账号清单");
        }
        if (mode == PullTaskSelectionMode.MANUAL
                && (distinct.size() != request.supplementCount()
                || distinct.size() != request.accountIds().size())) {
            throw new BusinessException(ErrorCode.VALIDATION, "手动选择账号数必须等于补充数量");
        }
        return mode;
    }

    private List<PullTaskStationCandidateVO> select(
            PullTaskStationSupplementDTO request,
            PullTaskSelectionMode mode,
            List<PullTaskStationCandidateVO> candidates) {
        if (mode == PullTaskSelectionMode.AUTOMATIC) {
            if (candidates.size() < request.supplementCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前候选站台数量不足");
            }
            return candidates;
        }
        Map<Long, PullTaskStationCandidateVO> byId = new HashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.accountId(), candidate));
        List<PullTaskStationCandidateVO> selected = request.accountIds().stream()
                .map(byId::get).filter(Objects::nonNull).toList();
        if (selected.size() != request.supplementCount()) {
            throw new BusinessException(ErrorCode.VALIDATION, "所选账号不在当前可用候选中");
        }
        return selected;
    }

    private void insertSelections(
            PullTaskGroupExecution execution,
            PullTaskStationSupplementDTO request,
            PullTaskSelectionMode mode,
            List<PullTaskStationCandidateVO> candidates,
            long now) {
        List<PullTaskGroupAccount> existing = stations(execution.getId());
        int nextSeq = nextRoleSeq(existing);
        int inserted = 0;
        for (PullTaskStationCandidateVO candidate : candidates) {
            if (inserted >= request.supplementCount()) {
                break;
            }
            try {
                insertStation(execution, candidate, mode, nextSeq++, now);
                inserted++;
            } catch (DuplicateKeyException exception) {
                if (mode == PullTaskSelectionMode.MANUAL) {
                    throw new BusinessException(ErrorCode.CONFLICT, "所选站台已用于当前群");
                }
            }
        }
        if (inserted < request.supplementCount()) {
            throw new BusinessException(ErrorCode.CONFLICT, "可锁定站台数量不足，请刷新后重试");
        }
    }

    private void insertStation(
            PullTaskGroupExecution execution,
            PullTaskStationCandidateVO candidate,
            PullTaskSelectionMode mode,
            int roleSeq,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setAccountId(candidate.accountId());
        row.setAccountPhone(candidate.accountPhone());
        row.setRoleType(PullTaskGroupAccountRole.STATION.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        row.setSelectionMode(mode.code());
        row.setEntryMode(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.accountMapper().insert(row) != 1 || row.getId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充站台指令写入失败");
        }
    }

    private List<PullTaskStationCandidateVO> candidates(
            long groupId,
            PullTaskGroupExecution execution,
            PullTaskStationCandidates current) {
        Set<Long> excluded = new HashSet<>();
        stations(execution.getId()).stream()
                .map(PullTaskGroupAccount::getAccountId).forEach(excluded::add);
        current.accounts().stream()
                .map(ProtocolAccountRef::armadaAccountId).forEach(excluded::add);
        Map<Long, PullTaskStationCandidateVO> result = new HashMap<>();
        for (ProtocolAccountRef account : safe(
                resources.accountLookup().findOnlineNormalByGroupId(groupId))) {
            if (account != null && !excluded.contains(account.armadaAccountId())) {
                result.putIfAbsent(account.armadaAccountId(), new PullTaskStationCandidateVO(
                        account.armadaAccountId(), account.wsPhone()));
            }
        }
        return result.values().stream().sorted(
                java.util.Comparator.comparingLong(PullTaskStationCandidateVO::accountId)).toList();
    }

    private void activate(PullTaskGroupExecution execution, long now) {
        PullTaskResourceSupplementTransition transition =
                new PullTaskResourceSupplementTransition(
                        new PullTaskResourceSupplementTransition.Scope(
                                execution.getTaskId(), execution.getId(),
                                execution.getVersion(), now),
                        new PullTaskResourceSupplementTransition.Expected(
                                SUPPLEMENTABLE_STATUSES,
                                PullTaskWaitResourceType.STATION.code(),
                                SUPPLEMENTABLE_STAGES),
                        new PullTaskResourceSupplementTransition.Target(
                                PullTaskExecutionStatus.EXECUTING.code(),
                                PullTaskExecutionStage.PULL_EXECUTION.code()));
        if (resources.executionMapper().activateResourceSupplement(transition) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行行状态已变化，请刷新后重新补充站台");
        }
    }

    private void requireGroup(Long groupId) {
        if (groupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "站台账号分组不能为空");
        }
        resources.accountGroupService().requireExisting(groupId);
    }

    private List<PullTaskGroupAccount> stations(long executionId) {
        return safe(resources.accountMapper().selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()));
    }

    private static void requireStationWait(PullTaskGroupExecution execution) {
        if (!Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code())
                || !Objects.equals(execution.getWaitResourceType(),
                PullTaskWaitResourceType.STATION.code())
                || !SUPPLEMENTABLE_STAGES.contains(execution.getStage())) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行行当前不处于等待站台状态");
        }
    }

    private static int requiredCount(PullTaskStandardSetting setting) {
        return setting.getStationCountPerCall() == null
                ? 0 : Math.max(setting.getStationCountPerCall(), 0);
    }

    private static int nextRoleSeq(List<PullTaskGroupAccount> rows) {
        return rows.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    private static boolean supplementableParent(String status) {
        return PullTaskStandardStatus.EXECUTING.name().equals(status)
                || PullTaskStandardStatus.PAUSED.name().equals(status);
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record Context(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting) {
    }
}
