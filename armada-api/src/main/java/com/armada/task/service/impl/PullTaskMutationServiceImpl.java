package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.service.PullTaskMutationService;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拉群任务公共变更服务实现。
 *
 * <p>当前只承载普通拉群和拉群营销任务共用的软删除入口。删除范围由当前租户、任务类型和任务状态
 * 共同约束，最终状态校验在 Mapper 更新 SQL 中完成，避免列表状态与提交删除之间发生并发变化。</p>
 */
@Service
public class PullTaskMutationServiceImpl implements PullTaskMutationService {

    /** 拉群任务公共主表变更入口，租户条件由 MyBatis 租户拦截器注入。 */
    private final PullTaskMapper mapper;

    /**
     * 装配拉群任务公共变更服务。
     *
     * @param mapper 公共任务主表 Mapper
     */
    public PullTaskMutationServiceImpl(PullTaskMapper mapper) {
        this.mapper = mapper;
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
        return mapper.batchSoftDeleteAllowed(
                List.copyOf(distinctIds), System.currentTimeMillis());
    }
}
