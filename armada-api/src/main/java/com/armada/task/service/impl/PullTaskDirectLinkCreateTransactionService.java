package com.armada.task.service.impl;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskDirectLinkCreateMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.dto.PullTaskDirectCreateRequest;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 无草稿正式建单事务；任一配置、链接或数据包冲突整体回滚。 */
@Service
public class PullTaskDirectLinkCreateTransactionService {
    private static final int MEMBER_BATCH_SIZE = 500;
    private static final int GROUP_SUBJECT_MAX_LENGTH = 100;
    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private final PullTaskDirectLinkCreateMapper tasks;
    private final PullTaskGroupExecutionMapper executions;
    private final PullTaskMaterialMemberMapper members;
    private final PullTaskStandardCreateResources resources;

    /** 复用既有配置、群注册及数据包分配边界。 */
    public PullTaskDirectLinkCreateTransactionService(PullTaskDirectLinkCreateMapper tasks,
            PullTaskGroupExecutionMapper executions, PullTaskMaterialMemberMapper members,
            PullTaskStandardCreateResources resources) {
        this.tasks = tasks;
        this.executions = executions;
        this.members = members;
        this.resources = resources;
    }

    /** 插入正式任务、配置、待启动执行行和普通料子，并原子领取数据包。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTask create(PullTaskDirectCreateRequest request, AuthPrincipal principal,
            List<PullTaskDirectLinkPlanner.PlannedRow> rows) {
        var existing = tasks.selectByRequest(principal.userId(), request.requestId().toLowerCase(Locale.ROOT));
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        PullTask task = task(request, principal, rows, now);
        tasks.insert(task, request.requestId().toLowerCase(Locale.ROOT));
        var frozen = request.frozenSettings();
        if (frozen.groupSetting().avatarFileKey() != null && !frozen.groupSetting().avatarFileKey().isBlank()) {
            Long tenantId = TenantContext.get();
            if (tenantId == null || tenantId <= 0) {
                throw new BusinessException(ErrorCode.TENANT_MISSING);
            }
            resources.avatarService().reserveForBinding(tenantId, frozen.groupSetting().avatarFileKey().trim());
        }
        resources.settingWriter().insert(frozen, task.getId());
        resources.groupSettingWriter().insert(frozen.groupSetting(), task.getId());
        GroupLinkRegistryService registry = resources.groupLinkRegistryService();
        boolean newGroup = frozen.creationMode().isSimplifiedNewGroup();
        Map<String, Long> groupIds = newGroup ? Map.of() : registry.registerPullTaskTargets(rows.stream()
                .map(row -> row.execution().getNormalizedLink()).toList(), now);
        for (var row : rows) {
            PullTaskGroupExecution execution = row.execution();
            initialize(execution, task.getId(), now);
            if (newGroup) {
                String subject = frozen.groupSetting().groupName().trim() + "-" + execution.getSeq();
                if (subject.length() > GROUP_SUBJECT_MAX_LENGTH) {
                    throw new BusinessException(ErrorCode.VALIDATION, "群名称追加序号后不能超过 100 个字符，请缩短群名称");
                }
                execution.setStage(PullTaskExecutionStage.GROUP_CREATE.code());
                execution.setCreateStep(PullTaskGroupCreateStep.SELECT_ROLES.code());
                execution.setGroupSubject(subject);
            } else {
                Long groupLinkId = groupIds.get(execution.getNormalizedLink());
                if (groupLinkId == null || groupLinkId <= 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "群链接注册结果缺失，请刷新后重试");
                }
                execution.setGroupLinkId(groupLinkId);
            }
            executions.insertInitialized(execution);
            insertMembers(row, now);
        }
        resources.dataPackageSourceService().claim(rows.stream().map(PullTaskDirectLinkPlanner.PlannedRow::execution).toList(),
                frozen.creationMode());
        return task;
    }

    private static PullTask task(PullTaskDirectCreateRequest request, AuthPrincipal principal,
            List<PullTaskDirectLinkPlanner.PlannedRow> rows, long now) {
        var task = new PullTask();
        task.setTaskType(PullTaskType.STANDARD);
        task.setMode(NORMAL_LINK_MODE);
        task.setCreationMode(request.frozenSettings().creationMode());
        task.setCreatorDeleteAfterTakeover(Boolean.TRUE.equals(
                request.frozenSettings().creatorDeleteAfterTakeover()) ? 1 : 0);
        task.setStatus(PullTaskStandardStatus.WAIT_START.name());
        task.setTaskName(request.taskName().trim());
        task.setRemark(request.remark());
        task.setConfigJson("{}");
        task.setOperatorName(principal.nickname() == null || principal.nickname().isBlank()
                ? principal.username() : principal.nickname());
        task.setCreatedBy(principal.userId());
        task.setGroupCount(rows.size());
        task.setExpectedPullCount(rows.stream().mapToInt(row -> row.members().size()).sum());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private static void initialize(PullTaskGroupExecution row, long taskId, long now) {
        row.setTaskId(taskId);
        row.setExecutionStatus(PullTaskExecutionStatus.WAIT_START.code());
        row.setStage(PullTaskExecutionStage.DIRECT_PULLER_JOIN.code());
        row.setAttemptNo(1);
        row.setCreateAttemptCount(0);
        row.setManualPaused(0);
        row.setNextManagerIndex(0);
        row.setNextPullerIndex(0);
        row.setPullerAssignmentSeq(0L);
        row.setNextRunAt(0L);
        row.setVersion(1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
    }

    private void insertMembers(PullTaskDirectLinkPlanner.PlannedRow row, long now) {
        for (var member : row.members()) {
            member.setGroupExecutionId(row.execution().getId());
            member.setAdminRequired(0);
            member.setAdminStatus(PullTaskMaterialAdminStatus.NOT_REQUIRED.code());
            member.setPullStatus(PullTaskMaterialPullStatus.UNCONSUMED.code());
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
        }
        for (int offset = 0; offset < row.members().size(); offset += MEMBER_BATCH_SIZE) {
            members.batchInsert(row.members().subList(offset, Math.min(offset + MEMBER_BATCH_SIZE, row.members().size())));
        }
    }
}
