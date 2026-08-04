package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 普通群链接草稿的事务写入组件。
 *
 * <p>独立成一个 bean 而不是写在编排服务里，是因为编排服务要在事务外完成最坏 40 秒的
 * 公开邀请页预检；Spring 的自调用不走代理，事务边界只能落在另一个 bean 上。把这四个写操作
 * 收在这里，编排服务本身就不需要也不允许标 {@code @Transactional}。</p>
 */
@Component
public class PullTaskStandardDraftWriter {

    /** 草稿期的占位任务名；正式名称在提交时才写入。 */
    private static final String DRAFT_TASK_NAME = "未命名草稿";

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskMaterialMemberMapper materialMapper;

    /**
     * 创建草稿写入组件。
     *
     * @param pullTaskMapper  任务主表数据访问
     * @param executionMapper 执行行数据访问
     * @param materialMapper  料子成员数据访问
     */
    public PullTaskStandardDraftWriter(PullTaskMapper pullTaskMapper,
                                       PullTaskGroupExecutionMapper executionMapper,
                                       PullTaskMaterialMemberMapper materialMapper) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.materialMapper = materialMapper;
    }

    /**
     * 取当前用户的草稿，没有就建一条。
     *
     * @param userId       创建人用户 ID
     * @param operatorName 操作员展示名快照
     * @param now          当前时间(epoch 毫秒)
     * @return 复用或新建的草稿任务行
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTask ensureDraft(long userId, String operatorName, long now) {
        PullTask existing = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (existing != null) {
            return existing;
        }
        PullTask draft = new PullTask();
        draft.setTaskName(DRAFT_TASK_NAME);
        draft.setOperatorName(operatorName);
        draft.setCreatedBy(userId);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        pullTaskMapper.insertDraft(draft);
        return draft;
    }

    /**
     * 追加本批匹配好的执行行与其料子成员。
     *
     * @param taskId 草稿任务 ID
     * @param rows   本批执行行及各自的料子；空集合直接返回
     * @param now    当前时间(epoch 毫秒)
     */
    @Transactional(rollbackFor = Exception.class)
    public void append(long taskId, List<AppendRow> rows, long now) {
        for (AppendRow row : rows) {
            PullTaskGroupExecution execution = row.execution();
            execution.setTaskId(taskId);
            execution.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
            execution.setStage(PullTaskExecutionStage.LINK_VALIDATION.code());
            execution.setManualPaused(0);
            execution.setNextManagerIndex(0);
            execution.setNextPullerIndex(0);
            execution.setNextRunAt(0L);
            execution.setVersion(1);
            execution.setCreatedAt(now);
            execution.setUpdatedAt(now);
            executionMapper.insertDraft(execution);
            insertMembers(execution.getId(), row.members(), now);
        }
    }

    /**
     * 删除草稿下的单条执行行及其料子。
     *
     * <p>先删料子、再删执行行：执行行删除带 {@code execution_status = 0} 守卫，
     * 返回 0 说明已被冻结，此时抛业务异常让整笔回滚，料子随之恢复。</p>
     *
     * @param taskId 草稿任务 ID
     * @param rowId  执行行 ID
     * @throws BusinessException 执行行不存在、不属于该草稿或已冻结时
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeRow(long taskId, long rowId) {
        materialMapper.deleteByExecution(rowId);
        if (executionMapper.deleteDraftRow(taskId, rowId) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "该执行行不存在或已提交，无法移除");
        }
    }

    /**
     * 清空草稿下的全部执行行与料子，保留草稿任务行本身。
     *
     * @param taskId 草稿任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAll(long taskId) {
        for (PullTaskGroupExecution row : executionMapper.selectByTaskId(taskId)) {
            materialMapper.deleteByExecution(row.getId());
        }
        executionMapper.deleteDraftByTaskId(taskId);
    }

    /**
     * 批量写入某条执行行的料子成员。
     *
     * @param executionId 执行行 ID
     * @param members     料子成员；空集合直接返回
     * @param now         当前时间(epoch 毫秒)
     */
    private void insertMembers(Long executionId, List<PullTaskMaterialMember> members, long now) {
        if (members.isEmpty()) {
            return;
        }
        for (PullTaskMaterialMember member : members) {
            member.setGroupExecutionId(executionId);
            member.setPullStatus(PullTaskMaterialPullStatus.UNCONSUMED.code());
            member.setAdminStatus(Integer.valueOf(1).equals(member.getAdminRequired())
                    ? PullTaskMaterialAdminStatus.PENDING.code()
                    : PullTaskMaterialAdminStatus.NOT_REQUIRED.code());
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
        }
        materialMapper.batchInsert(members);
    }

    /**
     * 一条待写入的执行行及其料子成员。
     *
     * @param execution 执行行；taskId、createdAt、updatedAt 由本组件填写
     * @param members   该执行行的料子；groupExecutionId、createdAt、updatedAt 由本组件填写
     */
    public record AppendRow(PullTaskGroupExecution execution, List<PullTaskMaterialMember> members) {
    }
}
