package com.armada.hyperlink.strategy.service;

import com.armada.hyperlink.strategy.mapper.HyperlinkStrategyMapper;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import com.armada.hyperlink.strategy.model.enums.HyperlinkStrategyScope;
import com.armada.hyperlink.task.service.HyperlinkTaskConfigurationFactory;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** 创建和更新任务独占策略快照；任务运行不直接引用可编辑模板。 */
@Service
public class HyperlinkTaskStrategyService {

    private final HyperlinkStrategyMapper mapper;
    private final HyperlinkStrategySnapshotCodec snapshotCodec;

    public HyperlinkTaskStrategyService(HyperlinkStrategyMapper mapper,
            HyperlinkStrategySnapshotCodec snapshotCodec) {
        this.mapper = mapper;
        this.snapshotCodec = snapshotCodec;
    }

    /** 在任务写入前创建未绑定 owner 的快照，外层事务失败时一并回滚。 */
    public HyperlinkStrategy createSnapshot(Long sourceStrategyId,
            HyperlinkTaskConfigurationFactory.Normalized value, long createdBy, long now) {
        if (sourceStrategyId != null) {
            HyperlinkStrategy source = mapper.selectById(sourceStrategyId);
            if (source == null || !Boolean.TRUE.equals(source.getEnabled())) {
                throw validation("引用的策略不存在、已停用或已删除");
            }
        }
        HyperlinkStrategy entity = snapshot(value, now);
        entity.setStrategyScope(HyperlinkStrategyScope.TASK_SNAPSHOT.code());
        entity.setSourceStrategyId(sourceStrategyId);
        entity.setEnabled(true);
        entity.setVersion(1);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(now);
        mapper.insert(entity);
        return entity;
    }

    /** 任务主键生成后补齐一对一 owner。 */
    public void attachOwner(long strategyId, long taskId, long now) {
        if (mapper.attachTaskOwner(strategyId, taskId, now) != 1) {
            throw conflict();
        }
    }

    /** 未开始任务编辑时更新独占快照；任务行的乐观锁负责并发仲裁。 */
    public void updateSnapshot(long taskId, Long strategyId,
            HyperlinkTaskConfigurationFactory.Normalized value,
            long now) {
        if (strategyId == null) {
            throw conflict();
        }
        HyperlinkStrategy entity = snapshot(value, now);
        entity.setId(strategyId);
        if (mapper.updateTaskSnapshot(entity, taskId) != 1) {
            throw conflict();
        }
    }

    private HyperlinkStrategy snapshot(
            HyperlinkTaskConfigurationFactory.Normalized value, long now) {
        HyperlinkStrategySnapshotCodec.Encoded filter = snapshotCodec.encode(value.accountFilter());
        HyperlinkStrategy entity = new HyperlinkStrategy();
        entity.setTaskType(value.taskMode().code());
        entity.setAccountFilter(filter.json());
        entity.setConcurrentNum(value.maxExecutingAccounts());
        entity.setMaxUseAccount(value.maxUseAccounts());
        entity.setAccountMaxSendNum(value.maxSendPerAccount());
        entity.setTaskIntervalMinutes(value.cycleIntervalMinutes());
        entity.setUpdatedAt(now);
        return entity;
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                "任务发送策略已变化，请刷新后重试");
    }
}
