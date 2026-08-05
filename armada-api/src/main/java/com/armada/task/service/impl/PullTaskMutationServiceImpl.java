package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.vo.PullTaskAvatarReference;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.armada.task.service.PullTaskMutationService;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 拉群任务公共变更服务实现。
 *
 * <p>当前只承载普通拉群和拉群营销任务共用的软删除入口。删除范围由当前租户、任务类型和任务状态
 * 共同约束，最终状态校验在 Mapper 更新 SQL 中完成，避免列表状态与提交删除之间发生并发变化。</p>
 */
@Service
public class PullTaskMutationServiceImpl implements PullTaskMutationService {

    private static final Logger log = LoggerFactory.getLogger(PullTaskMutationServiceImpl.class);

    /** 拉群任务公共主表变更入口，租户条件由 MyBatis 租户拦截器注入。 */
    private final PullTaskMapper mapper;
    private final PullTaskStandardGroupSettingMapper standardGroupSettingMapper;
    private final PullTaskGroupAvatarService avatarService;

    /**
     * 装配拉群任务公共变更服务。
     *
     * @param mapper 公共任务主表 Mapper
     */
    public PullTaskMutationServiceImpl(
            PullTaskMapper mapper,
            PullTaskStandardGroupSettingMapper standardGroupSettingMapper,
            PullTaskGroupAvatarService avatarService) {
        this.mapper = mapper;
        this.standardGroupSettingMapper = standardGroupSettingMapper;
        this.avatarService = avatarService;
    }

    /**
     * 按任务类型和当前状态批量软删除当前租户任务。
     *
     * <p>空请求直接返回 0；有效 ID 会去空、去重后一次提交 Mapper。拉群营销仅允许删除草稿，
     * 普通拉群保留待开始、已完成和已结束状态可删除的原有规则。</p>
     *
     * @param ids 待删除任务 ID，可包含空值或重复值
     * @return 实际被软删除的任务数量
     */
    @Override
    @Transactional
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                distinctIds.add(id);
            }
        }
        if (distinctIds.isEmpty()) {
            return 0;
        }
        List<Long> taskIds = List.copyOf(distinctIds);
        List<PullTaskAvatarReference> avatarReferences =
                standardGroupSettingMapper.selectActiveAvatarReferencesByTaskIds(taskIds);
        int deleted = mapper.batchSoftDeleteAllowed(taskIds, System.currentTimeMillis());
        if (deleted > 0 && avatarReferences != null && !avatarReferences.isEmpty()) {
            deleteAvatarsAfterCommit(List.copyOf(avatarReferences));
        }
        return deleted;
    }

    private void deleteAvatarsAfterCommit(List<PullTaskAvatarReference> references) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteAvatars(references);
                        }
                    });
            return;
        }
        deleteAvatars(references);
    }

    private void deleteAvatars(List<PullTaskAvatarReference> references) {
        for (PullTaskAvatarReference reference : references) {
            Long previousTenantId = TenantContext.get();
            try {
                TenantContext.set(reference.tenantId());
                avatarService.delete(reference.tenantId(), reference.avatarFileKey());
            } catch (RuntimeException exception) {
                log.warn(
                        "拉群任务头像提交后删除失败 tenantId={} taskId={} avatarFileKey={} errorType={}",
                        reference.tenantId(), reference.taskId(), reference.avatarFileKey(),
                        exception.getClass().getSimpleName());
            } finally {
                if (previousTenantId == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previousTenantId);
                }
            }
        }
    }
}
