package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.model.dto.PullTaskPullWaveCollectionWake;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 回执收口调用后唤醒匹配的收集态波次，不触碰派发态时钟。 */
@Service
public class PullTaskPullWaveProgressService {

    private final PullTaskPullWaveMapper waveMapper;
    private final PullTaskGroupExecutionMapper executionMapper;

    /** @param waveMapper 波次 Mapper @param executionMapper 执行行 Mapper */
    public PullTaskPullWaveProgressService(
            PullTaskPullWaveMapper waveMapper,
            PullTaskGroupExecutionMapper executionMapper) {
        this.waveMapper = waveMapper;
        this.executionMapper = executionMapper;
    }

    /** 只在波次和执行行身份仍匹配且处于收集态时把调度时间提前到 now。 */
    @Transactional(rollbackFor = Exception.class)
    public void wakeCollecting(
            long tenantId, long executionId, long waveId, long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            if (waveMapper.wakeCollecting(
                    waveId, executionId, PullTaskPullWaveStatus.COLLECTING.code(), now) != 1) {
                return;
            }
            executionMapper.wakePullWaveCollection(new PullTaskPullWaveCollectionWake(
                    executionId, waveId,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.PULL_EXECUTION.code(), now, now));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
