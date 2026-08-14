package com.armada.task.scheduler;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskMemberQueryDefer;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务内请求成员查询并释放当前执行租约，避免调度线程轮询或阻塞。 */
@Service
public class PullTaskMemberQueryAwaitService {

    private final PullTaskMemberQueryService queryService;
    private final PullTaskGroupExecutionMapper executionMapper;

    public PullTaskMemberQueryAwaitService(
            PullTaskMemberQueryService queryService,
            PullTaskGroupExecutionMapper executionMapper) {
        this.queryService = queryService;
        this.executionMapper = executionMapper;
    }

    /** AVAILABLE/FAILED 保留租约给调用方回写；PENDING 原子释放租约等待结果唤醒。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskMemberQueryResult readOrDefer(
            long tenantId,
            PullTaskMemberQueryRequest request,
            int expectedVersion,
            String lockOwner,
            int expectedStage,
            long now) {
        return readOrDefer(tenantId, request, expectedVersion, lockOwner,
                expectedStage, now, false);
    }

    /** discovery 使用首次持久化的 actor/targets 读取，候选顺序变化不会重复发命令。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskMemberQueryResult readOrDeferFrozen(
            long tenantId,
            PullTaskMemberQueryRequest request,
            int expectedVersion,
            String lockOwner,
            int expectedStage,
            long now) {
        return readOrDefer(tenantId, request, expectedVersion, lockOwner,
                expectedStage, now, true);
    }

    private PullTaskMemberQueryResult readOrDefer(
            long tenantId,
            PullTaskMemberQueryRequest request,
            int expectedVersion,
            String lockOwner,
            int expectedStage,
            long now,
            boolean frozen) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            PullTaskMemberQueryResult result = frozen
                    ? queryService.requestOrReadFrozen(request, now)
                    : queryService.requestOrRead(request, now);
            if (result.state() != PullTaskMemberQueryResult.State.PENDING) {
                return result;
            }
            if (result.nextCheckAt() == null || result.nextCheckAt() <= now) {
                throw new BusinessException(ErrorCode.CONFLICT, "成员查询等待截止时间非法");
            }
            PullTaskMemberQueryDefer defer = new PullTaskMemberQueryDefer(
                    request.groupExecutionId(), request.taskId(), expectedVersion,
                    PullTaskExecutionStatus.EXECUTING.code(), expectedStage, lockOwner,
                    result.nextCheckAt(), now);
            if (executionMapper.deferForMemberQuery(defer) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "成员查询等待时执行租约已变化");
            }
            return result;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }
}
