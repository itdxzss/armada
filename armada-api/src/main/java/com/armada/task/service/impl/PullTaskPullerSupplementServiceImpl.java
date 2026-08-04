package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskPullerSupplementDTO;
import com.armada.task.model.dto.PullTaskResourceSupplementTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskPullerCandidateVO;
import com.armada.task.model.vo.PullTaskPullerOptionRoleVO;
import com.armada.task.model.vo.PullTaskPullerSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.PullTaskPullerSupplementService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 补充拉手的候选过滤、四种组合冻结与检查点回退实现。 */
@Service
public class PullTaskPullerSupplementServiceImpl implements PullTaskPullerSupplementService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final List<Integer> SUPPLEMENTABLE_STATUSES =
            List.of(PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final List<Integer> SUPPLEMENTABLE_STAGES = List.of(
            PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
            PullTaskExecutionStage.PULLER_INVITE.code(),
            PullTaskExecutionStage.PULL_EXECUTION.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskPullerSupplementResources resources;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;

    public PullTaskPullerSupplementServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskPullerSupplementResources resources,
            PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.resources = resources;
        this.dispatchTrigger = dispatchTrigger;
    }

    @Override
    @Transactional(readOnly = true)
    public PullTaskPullerSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId) {
        Context context = context(taskId, executionId);
        Long groupId = accountGroupId == null
                ? context.setting().getPullerGroupId() : accountGroupId;
        requireGroup(groupId);
        List<PullTaskGroupAccount> pullers = pullers(executionId);
        int current = (int) pullers.stream().filter(
                PullTaskPullerSupplementServiceImpl::currentPuller).count();
        int required = requiredCount(context.setting());
        return new PullTaskPullerSupplementOptionsVO(
                current, required, Math.max(required - current, 0), groupId,
                managerInviteAvailable(executionId), pullers.stream()
                .map(PullTaskPullerSupplementServiceImpl::role).toList(),
                candidates(groupId, pullers));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supplement(
            long taskId, long executionId, PullTaskPullerSupplementDTO request) {
        Request requestState = validateRequest(request);
        Context context = context(taskId, executionId);
        requirePullerWait(context.execution());
        requireGroup(request.accountGroupId());
        List<PullTaskGroupAccount> existing = pullers(executionId);
        int availableSlots = availableSlots(context.setting(), existing);
        if (request.supplementCount() > availableSlots) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充数量超过当前拉手缺口");
        }
        if (requestState.entryMode() == PullTaskAccountEntryMode.MANAGER_INVITE
                && !managerInviteAvailable(executionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前没有可执行邀请的管理员");
        }
        List<PullTaskPullerCandidateVO> candidates =
                candidates(request.accountGroupId(), existing);
        List<PullTaskPullerCandidateVO> selected = select(request, requestState, candidates);
        long now = System.currentTimeMillis();
        insertSelections(context.execution(), requestState, selected, existing, now);
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
            throw new BusinessException(ErrorCode.CONFLICT, "当前任务状态不允许补充拉手");
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

    private Request validateRequest(PullTaskPullerSupplementDTO request) {
        if (request == null || request.accountGroupId() == null
                || request.supplementCount() == null || request.supplementCount() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉手分组和补充数量不能为空");
        }
        PullTaskSelectionMode selectionMode =
                PullTaskSelectionMode.fromCode(request.selectionMode());
        PullTaskAccountEntryMode entryMode =
                PullTaskAccountEntryMode.fromCode(request.entryMode());
        if (selectionMode == null || (entryMode != PullTaskAccountEntryMode.JOIN_BY_LINK
                && entryMode != PullTaskAccountEntryMode.MANAGER_INVITE)) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉手选择或进群方式无效");
        }
        Set<Long> distinct = new HashSet<>(request.accountIds());
        if (selectionMode == PullTaskSelectionMode.AUTOMATIC && !distinct.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "自动选择不应传入账号清单");
        }
        if (selectionMode == PullTaskSelectionMode.MANUAL
                && (distinct.size() != request.supplementCount()
                || distinct.size() != request.accountIds().size())) {
            throw new BusinessException(ErrorCode.VALIDATION, "手动选择账号数必须等于补充数量");
        }
        return new Request(selectionMode, entryMode, request.supplementCount());
    }

    private List<PullTaskPullerCandidateVO> select(
            PullTaskPullerSupplementDTO request,
            Request state,
            List<PullTaskPullerCandidateVO> candidates) {
        if (state.selectionMode() == PullTaskSelectionMode.AUTOMATIC) {
            if (candidates.size() < request.supplementCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前候选拉手数量不足");
            }
            return candidates;
        }
        Map<Long, PullTaskPullerCandidateVO> byId = new HashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.accountId(), candidate));
        List<PullTaskPullerCandidateVO> selected = request.accountIds().stream()
                .map(byId::get).filter(Objects::nonNull).toList();
        if (selected.size() != request.supplementCount()) {
            throw new BusinessException(ErrorCode.VALIDATION, "所选账号不在当前可用候选中");
        }
        return selected;
    }

    private void insertSelections(
            PullTaskGroupExecution execution,
            Request request,
            List<PullTaskPullerCandidateVO> candidates,
            List<PullTaskGroupAccount> existing,
            long now) {
        int targetCount = request.supplementCount();
        int nextSeq = nextRoleSeq(existing);
        int inserted = 0;
        for (PullTaskPullerCandidateVO candidate : candidates) {
            if (inserted >= targetCount) {
                break;
            }
            try {
                PullTaskGroupAccount row = insertPuller(
                        execution, candidate, nextSeq++, request, now);
                if (request.entryMode() == PullTaskAccountEntryMode.JOIN_BY_LINK) {
                    insertLinkAction(execution, row, now);
                }
                inserted++;
            } catch (DuplicateKeyException exception) {
                if (request.selectionMode() == PullTaskSelectionMode.MANUAL) {
                    throw new BusinessException(ErrorCode.CONFLICT, "所选拉手已被占用或重复选择");
                }
            }
        }
        if (inserted < targetCount) {
            throw new BusinessException(ErrorCode.CONFLICT, "可占用拉手数量不足，请刷新后重试");
        }
    }

    private PullTaskGroupAccount insertPuller(
            PullTaskGroupExecution execution,
            PullTaskPullerCandidateVO candidate,
            int roleSeq,
            Request request,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setAccountId(candidate.accountId());
        row.setAccountPhone(candidate.accountPhone());
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        row.setSelectionMode(request.selectionMode().code());
        row.setEntryMode(request.entryMode().code());
        row.setOccupiedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.accountMapper().insert(row) != 1 || row.getId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充拉手指令写入失败");
        }
        return row;
    }

    private void insertLinkAction(
            PullTaskGroupExecution execution, PullTaskGroupAccount target, long now) {
        PullTaskAccountAction action = new PullTaskAccountAction();
        action.setTaskId(execution.getTaskId());
        action.setGroupExecutionId(execution.getId());
        action.setActionType(PullTaskAccountActionType.JOIN_BY_LINK.code());
        action.setActorGroupAccountId(target.getId());
        action.setTargetGroupAccountId(target.getId());
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        if (resources.actionMapper().insertIfAbsent(action) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充拉手进群动作已存在");
        }
    }

    private void activate(PullTaskGroupExecution execution, long now) {
        PullTaskResourceSupplementTransition transition =
                new PullTaskResourceSupplementTransition(
                        new PullTaskResourceSupplementTransition.Scope(
                                execution.getTaskId(), execution.getId(),
                                execution.getVersion(), now),
                        new PullTaskResourceSupplementTransition.Expected(
                                SUPPLEMENTABLE_STATUSES,
                                PullTaskWaitResourceType.PULLER.code(),
                                SUPPLEMENTABLE_STAGES),
                        new PullTaskResourceSupplementTransition.Target(
                                PullTaskExecutionStatus.EXECUTING.code(),
                                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()));
        if (resources.executionMapper().activateResourceSupplement(transition) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行行状态已变化，请刷新后重新补充拉手");
        }
    }

    private List<PullTaskPullerCandidateVO> candidates(
            long groupId, List<PullTaskGroupAccount> existing) {
        List<ProtocolAccountRef> online = safe(
                resources.accountLookup().findOnlineNormalByGroupId(groupId));
        List<Long> ids = online.stream().filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> excluded = new HashSet<>();
        existing.stream().map(PullTaskGroupAccount::getAccountId).forEach(excluded::add);
        excluded.addAll(safe(resources.accountMapper().selectAccountIdsByAvailability(
                ids, PullTaskGroupAccountRole.PULLER.code(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code())));
        excluded.addAll(safe(resources.accountMapper().selectOccupiedAccountIds(
                ids, PullTaskGroupAccountRole.PULLER.code())));
        Map<Long, PullTaskPullerCandidateVO> result = new HashMap<>();
        for (ProtocolAccountRef account : online) {
            if (account != null && !excluded.contains(account.armadaAccountId())) {
                result.putIfAbsent(account.armadaAccountId(), new PullTaskPullerCandidateVO(
                        account.armadaAccountId(), account.wsPhone()));
            }
        }
        return result.values().stream().sorted(
                java.util.Comparator.comparingLong(PullTaskPullerCandidateVO::accountId)).toList();
    }

    private boolean managerInviteAvailable(long executionId) {
        List<PullTaskGroupAccount> managers = resources.accountMapper()
                .selectByExecutionAndRole(executionId, PullTaskGroupAccountRole.MANAGER.code())
                .stream().filter(PullTaskPullerSupplementServiceImpl::currentManager).toList();
        if (managers.isEmpty()) {
            return false;
        }
        Set<Long> activeIds = safe(resources.accountLookup().findActiveProtocolRefs(
                managers.stream().map(PullTaskGroupAccount::getAccountId).toList()))
                .stream().filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId)
                .collect(java.util.stream.Collectors.toSet());
        return managers.stream().anyMatch(row -> activeIds.contains(row.getAccountId()));
    }

    private void requireGroup(Long groupId) {
        if (groupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉手账号分组不能为空");
        }
        resources.accountGroupService().requireExisting(groupId);
    }

    private List<PullTaskGroupAccount> pullers(long executionId) {
        return safe(resources.accountMapper().selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.PULLER.code()));
    }

    private static void requirePullerWait(PullTaskGroupExecution execution) {
        if (!Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code())
                || !Objects.equals(execution.getWaitResourceType(),
                PullTaskWaitResourceType.PULLER.code())
                || !SUPPLEMENTABLE_STAGES.contains(execution.getStage())) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行行当前不处于等待拉手状态");
        }
    }

    private static int availableSlots(
            PullTaskStandardSetting setting, List<PullTaskGroupAccount> existing) {
        long occupied = existing.stream().filter(
                PullTaskPullerSupplementServiceImpl::occupiesPullerSlot).count();
        return Math.max(requiredCount(setting) - (int) occupied, 0);
    }

    private static int requiredCount(PullTaskStandardSetting setting) {
        return setting.getPullerCountPerGroup() == null
                ? 0 : Math.max(setting.getPullerCountPerGroup(), 0);
    }

    private static boolean currentPuller(PullTaskGroupAccount row) {
        return occupiesPullerSlot(row) && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
    }

    private static boolean occupiesPullerSlot(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && row.getReleasedAt() == null
                && !Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code());
    }

    private static boolean currentManager(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
    }

    private static int nextRoleSeq(List<PullTaskGroupAccount> rows) {
        return rows.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    private static PullTaskPullerOptionRoleVO role(PullTaskGroupAccount row) {
        return new PullTaskPullerOptionRoleVO(
                row.getId(), row.getAccountId(), row.getAccountPhone(),
                value(row.getMembershipStatus()), value(row.getAvailabilityStatus()),
                row.getReleasedAt() == null);
    }

    private static boolean supplementableParent(String status) {
        return PullTaskStandardStatus.EXECUTING.name().equals(status)
                || PullTaskStandardStatus.PAUSED.name().equals(status);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record Context(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting) {
    }

    private record Request(
            PullTaskSelectionMode selectionMode,
            PullTaskAccountEntryMode entryMode,
            int supplementCount) {
    }
}
