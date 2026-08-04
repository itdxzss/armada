package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerSupplementDTO;
import com.armada.task.model.dto.PullTaskManagerSupplementTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskManagerCandidateVO;
import com.armada.task.model.vo.PullTaskManagerOptionRoleVO;
import com.armada.task.model.vo.PullTaskManagerSupplementOptionsVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.PullTaskManagerSupplementService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每群固定一个管理员的补充选择、容量校验与检查点回退实现。 */
@Service
public class PullTaskManagerSupplementServiceImpl implements PullTaskManagerSupplementService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int REQUIRED_MANAGER_COUNT = 1;
    private static final List<Integer> SUPPLEMENTABLE_STATUSES =
            List.of(PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final List<Integer> SUPPLEMENTABLE_STAGES = List.of(
            PullTaskExecutionStage.MANAGER_JOIN.code(),
            PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
            PullTaskExecutionStage.PULLER_INVITE.code(),
            PullTaskExecutionStage.PULL_EXECUTION.code(),
            PullTaskExecutionStage.MATERIAL_ADMIN.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskManagerSupplementResources resources;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;

    /**
     * @param taskMapper 父任务 Mapper
     * @param settingMapper 冻结配置 Mapper
     * @param resources 执行行、角色账号与账号域依赖
     * @param dispatchTrigger 事务提交后的共享调度唤醒器
     */
    public PullTaskManagerSupplementServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskManagerSupplementResources resources,
            PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.resources = resources;
        this.dispatchTrigger = dispatchTrigger;
    }

    @Override
    @Transactional(readOnly = true)
    public PullTaskManagerSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId) {
        Context context = context(taskId, executionId);
        Long groupId = accountGroupId == null
                ? context.setting().getManagerGroupId() : accountGroupId;
        requireGroup(groupId);
        List<ProtocolAccountRef> groupAccounts = safe(
                resources.accountLookup().findOnlineNormalByGroupId(groupId));
        List<PullTaskGroupAccount> managers = managers(executionId);
        List<PullTaskGroupAccount> executors = executorRows(managers);
        int current = (int) managers.stream().filter(
                PullTaskManagerSupplementServiceImpl::currentManager).count();
        return new PullTaskManagerSupplementOptionsVO(
                current, REQUIRED_MANAGER_COUNT,
                Math.max(REQUIRED_MANAGER_COUNT - current, 0),
                groupId, !executors.isEmpty(), managers.stream().map(
                PullTaskManagerSupplementServiceImpl::role).toList(),
                executors.stream().map(PullTaskManagerSupplementServiceImpl::role).toList(),
                candidates(groupAccounts, managers));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supplement(
            long taskId, long executionId, PullTaskManagerSupplementDTO request) {
        validateRequest(request);
        Context context = context(taskId, executionId);
        requireManagerWait(context.execution());
        requireGroup(request.accountGroupId());
        List<PullTaskGroupAccount> managers = managers(executionId);
        ensureCapacity(managers);
        ProtocolAccountRef candidate = requireCandidate(request);
        PullTaskAccountEntryMode entryMode = PullTaskAccountEntryMode.fromCode(request.entryMode());
        PullTaskGroupAccount executor = requireExecutor(entryMode, request, managers);
        long now = System.currentTimeMillis();
        PullTaskGroupAccount inserted = insertManager(
                context.execution(), candidate, nextRoleSeq(managers), entryMode, now);
        insertEntryAction(context.execution(), inserted, executor, entryMode, now);
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
            throw new BusinessException(ErrorCode.CONFLICT, "当前任务状态不允许补充管理员");
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

    private void requireGroup(Long groupId) {
        if (groupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理员账号分组不能为空");
        }
        resources.accountGroupService().requireExisting(groupId);
    }

    private ProtocolAccountRef requireCandidate(PullTaskManagerSupplementDTO request) {
        return safe(resources.accountLookup().findOnlineNormalByGroupId(request.accountGroupId()))
                .stream()
                .filter(Objects::nonNull)
                .filter(account -> Objects.equals(account.armadaAccountId(), request.accountId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION, "所选账号不在当前在线正常候选中"));
    }

    private PullTaskGroupAccount requireExecutor(
            PullTaskAccountEntryMode entryMode,
            PullTaskManagerSupplementDTO request,
            List<PullTaskGroupAccount> managers) {
        if (entryMode == PullTaskAccountEntryMode.JOIN_BY_LINK) {
            if (request.executorRoleRowId() != null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION, "踩链接进群不应选择执行账号");
            }
            return null;
        }
        List<PullTaskGroupAccount> executors = executorRows(managers);
        return executors.stream()
                .filter(row -> Objects.equals(row.getId(), request.executorRoleRowId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION, "当前管理员不可用于邀请进群"));
    }

    private List<PullTaskGroupAccount> executorRows(List<PullTaskGroupAccount> managers) {
        List<PullTaskGroupAccount> eligible = managers.stream()
                .filter(PullTaskManagerSupplementServiceImpl::currentManager).toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        Set<Long> active = new HashSet<>(safe(resources.accountLookup()
                .findActiveProtocolRefs(eligible.stream()
                        .map(PullTaskGroupAccount::getAccountId).toList()))
                .stream().filter(Objects::nonNull)
                .map(ProtocolAccountRef::armadaAccountId).toList());
        return eligible.stream().filter(row -> active.contains(row.getAccountId())).toList();
    }

    private PullTaskGroupAccount insertManager(
            PullTaskGroupExecution execution,
            ProtocolAccountRef candidate,
            int roleSeq,
            PullTaskAccountEntryMode entryMode,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setAccountId(candidate.armadaAccountId());
        row.setAccountPhone(candidate.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(PullTaskGroupAccountSource.SUPPLEMENT.code());
        row.setSelectionMode(PullTaskSelectionMode.MANUAL.code());
        row.setEntryMode(entryMode.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            if (resources.accountMapper().insert(row) != 1 || row.getId() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "补充管理员指令写入失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "管理员已被选择或补充名额已占用");
        }
        return row;
    }

    private void insertEntryAction(
            PullTaskGroupExecution execution,
            PullTaskGroupAccount target,
            PullTaskGroupAccount executor,
            PullTaskAccountEntryMode entryMode,
            long now) {
        PullTaskAccountAction action = new PullTaskAccountAction();
        action.setTaskId(execution.getTaskId());
        action.setGroupExecutionId(execution.getId());
        action.setActionType(entryMode == PullTaskAccountEntryMode.JOIN_BY_LINK
                ? PullTaskAccountActionType.JOIN_BY_LINK.code()
                : PullTaskAccountActionType.INVITE_TO_GROUP.code());
        action.setActorGroupAccountId(executor == null ? target.getId() : executor.getId());
        action.setTargetGroupAccountId(target.getId());
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        if (resources.actionMapper().insertIfAbsent(action) != 1 || action.getId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "补充管理员进群动作已存在");
        }
    }

    private void activate(PullTaskGroupExecution execution, long now) {
        PullTaskManagerSupplementTransition transition =
                new PullTaskManagerSupplementTransition(
                        new PullTaskManagerSupplementTransition.Scope(
                                execution.getTaskId(), execution.getId(),
                                execution.getVersion(), now),
                        new PullTaskManagerSupplementTransition.Expected(
                                SUPPLEMENTABLE_STATUSES,
                                PullTaskWaitResourceType.MANAGER.code(),
                                SUPPLEMENTABLE_STAGES),
                        new PullTaskManagerSupplementTransition.Target(
                                PullTaskExecutionStatus.EXECUTING.code(),
                                PullTaskExecutionStage.MANAGER_JOIN.code()));
        if (resources.executionMapper().activateManagerSupplement(transition) != 1) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "执行行状态已变化，请刷新后重新补充管理员");
        }
    }

    private List<PullTaskGroupAccount> managers(long executionId) {
        return safe(resources.accountMapper().selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.MANAGER.code()));
    }

    private static List<PullTaskManagerCandidateVO> candidates(
            List<ProtocolAccountRef> accounts,
            List<PullTaskGroupAccount> managers) {
        Set<Long> selected = managers.stream().map(PullTaskGroupAccount::getAccountId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, PullTaskManagerCandidateVO> result = new HashMap<>();
        for (ProtocolAccountRef account : accounts) {
            if (account != null && !selected.contains(account.armadaAccountId())) {
                result.putIfAbsent(account.armadaAccountId(),
                        new PullTaskManagerCandidateVO(
                                account.armadaAccountId(), account.wsPhone()));
            }
        }
        return result.values().stream().sorted(
                java.util.Comparator.comparingLong(PullTaskManagerCandidateVO::accountId)).toList();
    }

    private static void validateRequest(PullTaskManagerSupplementDTO request) {
        if (request == null || request.accountGroupId() == null || request.accountId() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "管理员分组和候选账号不能为空");
        }
        PullTaskAccountEntryMode entryMode = PullTaskAccountEntryMode.fromCode(request.entryMode());
        if (entryMode != PullTaskAccountEntryMode.JOIN_BY_LINK
                && entryMode != PullTaskAccountEntryMode.MANAGER_INVITE) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理员进群方式无效");
        }
    }

    private static void requireManagerWait(PullTaskGroupExecution execution) {
        if (!Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.WAIT_RESOURCE.code())
                || !Objects.equals(execution.getWaitResourceType(),
                PullTaskWaitResourceType.MANAGER.code())
                || !SUPPLEMENTABLE_STAGES.contains(execution.getStage())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "执行行当前不处于等待管理员状态");
        }
    }

    private static void ensureCapacity(List<PullTaskGroupAccount> managers) {
        long occupied = managers.stream().filter(
                PullTaskManagerSupplementServiceImpl::occupiesManagerSlot).count();
        if (occupied >= REQUIRED_MANAGER_COUNT) {
            throw new BusinessException(ErrorCode.CONFLICT, "管理员名额已补足");
        }
    }

    private static boolean currentManager(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && !Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.FAILED.code());
    }

    private static boolean occupiesManagerSlot(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && !Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code())
                && !Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.FAILED.code());
    }

    private static int nextRoleSeq(List<PullTaskGroupAccount> managers) {
        return managers.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    private static PullTaskManagerOptionRoleVO role(PullTaskGroupAccount row) {
        return new PullTaskManagerOptionRoleVO(
                row.getId(), row.getAccountId(), row.getAccountPhone(),
                value(row.getMembershipStatus()), value(row.getAdminStatus()),
                value(row.getAvailabilityStatus()));
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
}
