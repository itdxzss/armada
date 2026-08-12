package com.armada.group.service.impl;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupBatchSubmitDTO;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupBatchTaskAcceptedVO;
import com.armada.group.model.vo.GroupBatchTaskDetailVO;
import com.armada.group.model.vo.GroupBatchTaskItemRow;
import com.armada.group.model.vo.GroupBatchTaskItemVO;
import com.armada.group.service.GroupBatchTaskService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群组列表批量刷新任务提交与进度实现。 */
@Service
public class GroupBatchTaskServiceImpl implements GroupBatchTaskService {

    private static final String BLOCKED_ITEM_DESCRIPTION = "当前群组状态异常，暂不支持刷新邀请链接";
    private static final String BLOCKED_ITEM_ERROR_CODE = "GROUP_STATE_BLOCKED";

    private final GroupBatchTaskMapper taskMapper;
    private final GroupBatchTaskItemMapper itemMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkHealthMapper healthMapper;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /** 创建批量任务服务。 */
    public GroupBatchTaskServiceImpl(
            GroupBatchTaskMapper taskMapper,
            GroupBatchTaskItemMapper itemMapper,
            GroupLinkMapper groupLinkMapper,
            GroupLinkHealthMapper healthMapper,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.healthMapper = healthMapper;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupBatchTaskAcceptedVO submitRefreshLinks(GroupBatchSubmitDTO dto, long operatorId) {
        return submit(GroupBatchTaskType.REFRESH_LINK, dto, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupBatchTaskAcceptedVO submitRefreshInfo(GroupBatchSubmitDTO dto, long operatorId) {
        return submit(GroupBatchTaskType.REFRESH_INFO, dto, operatorId);
    }

    @Override
    public GroupBatchTaskDetailVO detail(Long taskId) {
        GroupBatchTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "批量任务不存在");
        }
        GroupBatchTaskStatus status = GroupBatchTaskStatus.fromCode(task.getStatus());
        return new GroupBatchTaskDetailVO(
                task.getId(),
                GroupBatchTaskType.fromCode(task.getTaskType()).name(),
                status.name(),
                status.terminal(),
                task.getCreatedAt(),
                task.getCompletedAt(),
                valueOrZero(task.getTotalCount()),
                valueOrZero(task.getSuccessCount()),
                valueOrZero(task.getFailedCount()),
                items(taskId));
    }

    /**
     * 校验、落库并按类型排队批量任务。
     *
     * <p>范围严格限定为请求内去重后仍属当前租户且未删除的群，未选中的群不会被请求或回填。</p>
     */
    private GroupBatchTaskAcceptedVO submit(
            GroupBatchTaskType type, GroupBatchSubmitDTO dto, long operatorId) {
        List<Long> requested = distinctIds(dto);
        String requestId = requireRequestId(dto);
        GroupBatchTask existing = taskMapper.selectByRequestId(requestId);
        if (existing != null) {
            return accepted(existing);
        }
        List<Long> targets = visibleGroupIds(requested);
        Set<Long> blocked = type == GroupBatchTaskType.REFRESH_LINK
                ? refreshBlockedIds(targets)
                : Set.of();
        long now = System.currentTimeMillis();
        GroupBatchTask task = task(type, requestId, operatorId, targets.size(), blocked.size(), now);
        taskMapper.insert(task);
        itemMapper.batchInsert(items(task, targets, blocked, now));
        if (type == GroupBatchTaskType.REFRESH_INFO) {
            // 走批量档 trigger，由 findDue 的独立配额限速，绝不占用实时刷新名额。
            targets.forEach(id -> metadataSyncTaskService.enqueue(
                    id, GroupMetadataSyncTrigger.BATCH_REFRESH, now));
        }
        return accepted(task);
    }

    private List<GroupBatchTaskItem> items(
            GroupBatchTask task, List<Long> targets, Set<Long> blocked, long now) {
        List<GroupBatchTaskItem> rows = new ArrayList<>(targets.size());
        for (Long groupLinkId : targets) {
            GroupBatchTaskItem row = new GroupBatchTaskItem();
            row.setTenantId(task.getTenantId());
            row.setTaskId(task.getId());
            row.setGroupLinkId(groupLinkId);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            if (blocked.contains(groupLinkId)) {
                // 前端已对封禁/不可用群置灰；越过置灰的请求在提交阶段就终结，永不进入写路径。
                row.setStatus(GroupBatchTaskItemStatus.FAILED.code());
                row.setErrorCode(BLOCKED_ITEM_ERROR_CODE);
                row.setDescription(BLOCKED_ITEM_DESCRIPTION);
                row.setOperatedAt(now);
            } else {
                row.setStatus(GroupBatchTaskItemStatus.PENDING.code());
                // 判定基线取提交时刻：此后发生的任一次同步成功都说明该群快照已刷新。
                row.setBaselineSyncedAt(now);
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static GroupBatchTask task(
            GroupBatchTaskType type,
            String requestId,
            long operatorId,
            int totalCount,
            int blockedCount,
            long now) {
        boolean settledOnSubmit = blockedCount >= totalCount;
        GroupBatchTask task = new GroupBatchTask();
        task.setTenantId(TenantContext.get());
        task.setTaskType(type.code());
        // 全部目标都在提交阶段被拒时任务已无事可做，直接落终态，避免执行器永远等不到明细。
        task.setStatus(settledOnSubmit
                ? GroupBatchTaskStatus.COMPLETED.code()
                : GroupBatchTaskStatus.PENDING.code());
        task.setTotalCount(totalCount);
        task.setSuccessCount(0);
        task.setFailedCount(blockedCount);
        task.setRequestId(requestId);
        task.setCreatedBy(operatorId);
        task.setCreatedAt(now);
        task.setCompletedAt(settledOnSubmit ? now : null);
        return task;
    }

    private List<GroupBatchTaskItemVO> items(Long taskId) {
        List<GroupBatchTaskItemRow> rows = itemMapper.selectDetailRowsByTaskId(taskId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new GroupBatchTaskItemVO(
                        row.groupLinkId(),
                        row.groupJid(),
                        row.accountPhone(),
                        GroupBatchTaskItemStatus.fromCode(row.status()).name(),
                        row.description(),
                        row.operatedAt()))
                .toList();
    }

    private List<Long> visibleGroupIds(List<Long> requested) {
        List<GroupLink> visible = groupLinkMapper.selectActiveByIds(requested);
        if (visible == null || visible.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "所选群组不存在或已删除");
        }
        Set<Long> allowed = new LinkedHashSet<>();
        visible.forEach(link -> allowed.add(link.getId()));
        return requested.stream().filter(allowed::contains).toList();
    }

    private Set<Long> refreshBlockedIds(List<Long> targets) {
        List<Long> blocked = healthMapper.selectLinkRefreshBlockedIds(targets);
        return blocked == null ? Set.of() : Set.copyOf(blocked);
    }

    private static List<Long> distinctIds(GroupBatchSubmitDTO dto) {
        if (dto == null || dto.ids() == null || dto.ids().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请先勾选需要操作的群组");
        }
        List<Long> ids = dto.ids().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请先勾选需要操作的群组");
        }
        return ids;
    }

    private static String requireRequestId(GroupBatchSubmitDTO dto) {
        String requestId = dto.requestId() == null ? null : dto.requestId().trim();
        if (requestId == null || requestId.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少幂等键 requestId");
        }
        return requestId;
    }

    private static GroupBatchTaskAcceptedVO accepted(GroupBatchTask task) {
        return new GroupBatchTaskAcceptedVO(
                task.getId(),
                task.getCreatedAt(),
                GroupBatchTaskStatus.fromCode(task.getStatus()).name());
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
