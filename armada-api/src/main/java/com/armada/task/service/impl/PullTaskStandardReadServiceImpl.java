package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskStandardAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionAggregateCriteria;
import com.armada.task.model.dto.PullTaskStandardExecutionFilter;
import com.armada.task.model.dto.PullTaskStandardExecutionQuery;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.vo.PullTaskStandardActionVO;
import com.armada.task.model.vo.PullTaskStandardCallVO;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import com.armada.task.model.vo.PullTaskStandardExecutionDetailVO;
import com.armada.task.model.vo.PullTaskStandardExecutionSummaryVO;
import com.armada.task.model.vo.PullTaskStandardMaterialSummaryVO;
import com.armada.task.model.vo.PullTaskStandardMemberVO;
import com.armada.task.model.vo.PullTaskStandardResourceCountVO;
import com.armada.task.model.vo.PullTaskStandardRoleVO;
import com.armada.task.model.vo.PullTaskStandardTaskAggregate;
import com.armada.task.model.vo.PullTaskStandardTaskDetailVO;
import com.armada.task.model.vo.PullTaskStandardTaskSummaryVO;
import com.armada.task.service.PullTaskStandardReadService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从 6 张普通群链接事实表组装任务聚合、分页工作台和可追溯执行详情。 */
@Service
public class PullTaskStandardReadServiceImpl implements PullTaskStandardReadService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String DRAFT_STATUS = "DRAFT";
    private static final List<Integer> ALL_ACTION_STATUSES = Arrays.stream(
            PullTaskActionStatus.values()).map(PullTaskActionStatus::code).toList();

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardReadResources resources;

    /** @param taskMapper 任务主表入口 @param resources 普通群链接全部读事实入口 */
    public PullTaskStandardReadServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardReadResources resources) {
        this.taskMapper = taskMapper;
        this.resources = resources;
    }

    @Override
    @Transactional(readOnly = true)
    public PullTaskStandardTaskDetailVO task(long taskId) {
        PullTask task = requireTask(taskId);
        PullTaskStandardTaskAggregate aggregate = resources.readMapper()
                .selectTaskAggregates(PullTaskStandardAggregateCriteria.fromEnums(List.of(taskId)))
                .stream().findFirst().orElse(null);
        return new PullTaskStandardTaskDetailVO(
                task.getId(), task.getTaskName(), task.getStatus(), task.getGroupCount(),
                task.getExpectedPullCount(), task.getStartedAt(), task.getFinishedAt(),
                task.getCreatedAt(), task.getRemark(), List.of(), taskSummary(aggregate));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PullTaskStandardExecutionSummaryVO> executions(
            long taskId,
            PullTaskStandardExecutionQuery query) {
        requireTask(taskId);
        PullTaskStandardExecutionQuery safeQuery =
                query == null ? new PullTaskStandardExecutionQuery() : query;
        PullTaskStandardExecutionFilter filter = safeQuery.toFilter(taskId);
        long total = resources.readMapper().countExecutions(filter);
        if (total == 0) {
            return PageResult.of(
                    List.of(), safeQuery.getPage(), safeQuery.getPageSize(), 0);
        }
        List<PullTaskGroupExecution> rows = resources.readMapper().selectExecutionPage(
                filter, safeQuery.getOffset(), safeQuery.getPageSize());
        Map<Long, PullTaskStandardExecutionAggregate> aggregates = aggregateExecutions(rows);
        List<PullTaskStandardExecutionSummaryVO> result = rows.stream()
                .map(row -> summary(row, aggregates.get(row.getId())))
                .toList();
        return PageResult.of(
                result, safeQuery.getPage(), safeQuery.getPageSize(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public PullTaskStandardExecutionDetailVO execution(long taskId, long executionId) {
        PullTaskGroupExecution execution = requireExecution(taskId, executionId);
        PullTaskStandardExecutionAggregate aggregate = aggregateExecutions(List.of(execution))
                .get(executionId);
        PullTaskStandardReadFactMappers facts = resources.facts();
        return new PullTaskStandardExecutionDetailVO(
                summary(execution, aggregate), roles(executionId),
                facts.callMapper().selectByExecution(executionId).stream()
                        .map(PullTaskStandardReadServiceImpl::call).toList(),
                facts.actionMapper().selectByExecutionAndStatuses(
                                executionId, ALL_ACTION_STATUSES).stream()
                        .map(PullTaskStandardReadServiceImpl::action).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullTaskStandardMemberVO> members(long taskId, long executionId) {
        requireExecution(taskId, executionId);
        return resources.facts().materialMapper().selectByExecution(executionId).stream()
                .map(PullTaskStandardReadServiceImpl::member).toList();
    }

    private PullTask requireTask(long taskId) {
        PullTask task = taskMapper.selectLifecycle(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务不存在");
        }
        if (task.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(task.getMode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前任务不是普通群链接任务");
        }
        if (DRAFT_STATUS.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务草稿不存在");
        }
        return task;
    }

    private PullTaskGroupExecution requireExecution(long taskId, long executionId) {
        requireTask(taskId);
        PullTaskGroupExecution row = resources.executionMapper().selectById(executionId);
        if (row == null || row.getTaskId() == null || row.getTaskId() != taskId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群执行行不存在");
        }
        return row;
    }

    private Map<Long, PullTaskStandardExecutionAggregate> aggregateExecutions(
            List<PullTaskGroupExecution> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(PullTaskGroupExecution::getId).toList();
        return resources.readMapper().selectExecutionAggregates(
                        PullTaskStandardExecutionAggregateCriteria.fromEnums(ids)).stream()
                .collect(Collectors.toMap(
                        PullTaskStandardExecutionAggregate::getExecutionId, Function.identity()));
    }

    private List<PullTaskStandardRoleVO> roles(long executionId) {
        List<PullTaskStandardRoleVO> result = new ArrayList<>();
        for (PullTaskGroupAccountRole role : PullTaskGroupAccountRole.values()) {
            resources.facts().accountMapper()
                    .selectByExecutionAndRole(executionId, role.code()).stream()
                    .map(PullTaskStandardReadServiceImpl::role).forEach(result::add);
        }
        return result;
    }

    private static PullTaskStandardExecutionSummaryVO summary(
            PullTaskGroupExecution row,
            PullTaskStandardExecutionAggregate aggregate) {
        return new PullTaskStandardExecutionSummaryVO(
                row.getId(), value(row.getSeq()), row.getNormalizedLink(), row.getGroupJid(),
                value(row.getExecutionStatus()), value(row.getStage()),
                Integer.valueOf(1).equals(row.getManualPaused()),
                row.getWaitResourceType(),
                value(row.getValidMemberCount()), row.getReasonCode(), row.getReasonMessage(),
                row.getLastBusinessExecutedAt(), materialSummary(aggregate),
                resource(aggregate, ResourceRole.MANAGER),
                resource(aggregate, ResourceRole.PULLER),
                resource(aggregate, ResourceRole.STATION));
    }

    private static PullTaskStandardMaterialSummaryVO materialSummary(
            PullTaskStandardExecutionAggregate aggregate) {
        if (aggregate == null) {
            return null;
        }
        return new PullTaskStandardMaterialSummaryVO(
                value(aggregate.getTotalMemberCount()),
                value(aggregate.getSuccessfulMemberCount()),
                value(aggregate.getFailedMemberCount()),
                value(aggregate.getUnknownMemberCount()),
                value(aggregate.getUnconsumedMemberCount()),
                value(aggregate.getSubmittedMemberCount()),
                value(aggregate.getCanceledMemberCount()));
    }

    private static PullTaskStandardResourceCountVO resource(
            PullTaskStandardExecutionAggregate aggregate,
            ResourceRole role) {
        if (aggregate == null) {
            return null;
        }
        int current = role.current(aggregate);
        int planned = role.planned(aggregate);
        return new PullTaskStandardResourceCountVO(
                current, planned, Math.max(planned - current, 0));
    }

    private static PullTaskStandardTaskSummaryVO taskSummary(
            PullTaskStandardTaskAggregate aggregate) {
        if (aggregate == null) {
            return null;
        }
        int waitingResource = value(aggregate.getManagerShortageGroupCount())
                + value(aggregate.getPullerShortageGroupCount())
                + value(aggregate.getStationShortageGroupCount());
        return new PullTaskStandardTaskSummaryVO(
                value(aggregate.getTotalGroupCount()),
                value(aggregate.getExecutingGroupCount()), waitingResource,
                value(aggregate.getCompletedGroupCount()),
                value(aggregate.getFailedGroupCount()),
                value(aggregate.getAbandonedGroupCount()),
                value(aggregate.getTotalMemberCount()),
                value(aggregate.getSuccessfulMemberCount()),
                value(aggregate.getFailedMemberCount()),
                value(aggregate.getUnknownMemberCount()),
                value(aggregate.getUnconsumedMemberCount()));
    }

    private static PullTaskStandardRoleVO role(PullTaskGroupAccount row) {
        return new PullTaskStandardRoleVO(
                row.getId(), row.getAccountId(), row.getAccountPhone(), value(row.getRoleType()),
                value(row.getRoleSeq()), value(row.getMembershipStatus()),
                row.getMembershipReasonCode(), row.getMembershipReasonMessage(),
                row.getMembershipResultAt(),
                value(row.getAvailabilityStatus()), row.getUnavailableReasonCode(),
                row.getPullCallId());
    }

    private static PullTaskStandardCallVO call(PullTaskPullCall row) {
        return new PullTaskStandardCallVO(
                row.getId(), value(row.getCallSeq()), row.getPullerAccountId(),
                value(row.getPlannedMaterialCount()), value(row.getPlannedStationCount()),
                value(row.getCallStatus()), row.getReasonCode(), row.getReasonMessage(),
                row.getSubmittedAt(), row.getResultAt());
    }

    private static PullTaskStandardActionVO action(PullTaskAccountAction row) {
        return new PullTaskStandardActionVO(
                row.getId(), value(row.getActionType()), row.getActorGroupAccountId(),
                row.getTargetGroupAccountId(), value(row.getActionStatus()),
                row.getReasonCode(), row.getReasonMessage(), row.getSubmittedAt(), row.getResultAt());
    }

    private static PullTaskStandardMemberVO member(PullTaskMaterialMember row) {
        return new PullTaskStandardMemberVO(
                row.getId(), value(row.getMemberSeq()), row.getNormalizedPhone(),
                Integer.valueOf(1).equals(row.getAdminRequired()), row.getPullCallId(),
                value(row.getPullStatus()), row.getPullReasonCode(), row.getPullReasonMessage(),
                row.getWaJid(), value(row.getAdminStatus()), row.getAdminReasonCode());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private enum ResourceRole {
        MANAGER {
            @Override int current(PullTaskStandardExecutionAggregate row) {
                return value(row.getCurrentManagerCount());
            }
            @Override int planned(PullTaskStandardExecutionAggregate row) {
                return value(row.getRequiredManagerCount());
            }
        },
        PULLER {
            @Override int current(PullTaskStandardExecutionAggregate row) {
                return value(row.getCurrentPullerCount());
            }
            @Override int planned(PullTaskStandardExecutionAggregate row) {
                return value(row.getPlannedPullerCount());
            }
        },
        STATION {
            @Override int current(PullTaskStandardExecutionAggregate row) {
                return value(row.getCurrentStationCount());
            }
            @Override int planned(PullTaskStandardExecutionAggregate row) {
                return value(row.getPlannedStationCount());
            }
        };

        abstract int current(PullTaskStandardExecutionAggregate row);
        abstract int planned(PullTaskStandardExecutionAggregate row);
    }
}
