package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskActionSubmission;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.enums.PullTaskActionStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 执行行账号动作数据访问层。 */
@Mapper
public interface PullTaskAccountActionMapper {

    /**
     * 幂等地建立一个账号动作行。
     *
     * <p>唯一键 {@code (tenant_id, group_execution_id, action_type,
     * actor_group_account_id, target_group_account_id)} 本身就是幂等键，因此不设
     * requestId 列。服务重启后重放同一步会返回 0，调用方据此跳过，不重复发命令。</p>
     *
     * <p>踩链接入群没有真正的发起方，但 {@code actor_group_account_id} 仍必须写
     * 目标账号自身 ID：MySQL 唯一索引中 NULL 之间互不相等，留空会让同一账号的
     * 踩链接动作可以无限重复插入，幂等键形同虚设。</p>
     *
     * <p><b>{@code INSERT IGNORE} 的隐患：</b>MySQL 会把不止重复键错误降级为警告——
     * {@code actor_group_account_id}/{@code target_group_account_id} 为 NULL 这类
     * NOT NULL 违反会被静默地插成一行受影响数为 0 而不是抛异常，字符串截断也会被静默截断
     * 而不报错。调用方必须自行保证这两个字段非空，本方法不会在它们为 null 时报错。</p>
     *
     * <p>当行因幂等键已存在而被吸收（返回 0）时，{@code useGeneratedKeys} 不会回填
     * {@code id}：传入的 {@code row} 上 {@code id} 仍是调用前的值（通常为 null）。
     * 调用方看到返回值为 0 时不能假设 {@code row.getId()} 已经是数据库中既有行的 ID。</p>
     *
     * @param row 动作行；写入后回填 id
     * @return 新增行数；0 表示该动作已存在，此时 row 的 id 未被填充
     */
    int insertInitialized(PullTaskAccountAction row);

    /** 兼容业务入口；初始状态由 Java 枚举设置后再交给 XML 持久化。 */
    default int insertIfAbsent(PullTaskAccountAction row) {
        row.setActionStatus(PullTaskActionStatus.PENDING.code());
        return insertInitialized(row);
    }

    /**
     * 取执行行内待执行的动作，按 id 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 待执行动作
     */
    default List<PullTaskAccountAction> selectPending(long groupExecutionId) {
        return selectByExecutionAndStatuses(
                groupExecutionId, List.of(PullTaskActionStatus.PENDING.code()));
    }

    /**
     * 读取执行行内某一类动作的全部记录。
     *
     * @param groupExecutionId 执行行 ID
     * @param actionType 动作类型，取值见 PullTaskAccountActionType
     * @return 动作记录，按 id 升序
     */
    List<PullTaskAccountAction> selectByExecutionAndType(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("actionType") int actionType);

    /** 读取执行行内需要查询或回调收敛的动作，状态集合由业务层传入。 */
    List<PullTaskAccountAction> selectByExecutionAndStatuses(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("statuses") List<Integer> statuses);

    /**
     * 标记动作命令已提交。
     *
     * @param id 动作行 ID
     * @param commandId 协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数；0 表示该动作已不在待执行状态
     */
    default int markSubmitted(long id, String commandId, long now) {
        return transitionSubmitted(new PullTaskActionSubmission(
                id,
                PullTaskActionStatus.PENDING.code(),
                PullTaskActionStatus.SUBMITTED.code(),
                commandId,
                now));
    }

    /** 由 Java 明确传入前置态和目标态，CAS 提交账号动作。 */
    int transitionSubmitted(@Param("submission") PullTaskActionSubmission submission);

    /**
     * 把结果未知的动作重新置为已提交，以便只做事实复核并允许结果 CAS 收敛。
     *
     * @param id 动作行 ID
     * @param expectedStatus 允许的当前状态
     * @param submittedStatus 已提交状态
     * @param now 更新时间(epoch 毫秒)
     * @return 1 表示取得复核资格；0 表示动作状态已变化
     */
    int reopenForVerification(@Param("id") long id,
                              @Param("expectedStatus") int expectedStatus,
                              @Param("submittedStatus") int submittedStatus,
                              @Param("now") long now);

    /**
     * 回写动作结果。
     *
     * @param id 动作行 ID
     * @param actionStatus 动作结果，取值见 PullTaskActionStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数；0 表示动作已不在已提交状态，迟到结果不得覆盖终态
     */
    default int writeBackResult(long id,
                                int actionStatus,
                                String reasonCode,
                                String reasonMessage,
                                long now) {
        return transitionResult(new PullTaskFactTransition(
                id,
                List.of(PullTaskActionStatus.SUBMITTED.code()),
                actionStatus,
                PullTaskFactResult.reason(reasonCode, reasonMessage),
                now));
    }

    /** 从已提交/未知等允许状态 CAS 收敛动作结果。 */
    int transitionResult(@Param("transition") PullTaskFactTransition transition);

    /**
     * 协议回调按命令 ID 定位动作行。
     *
     * @param commandId 协议命令 ID
     * @return 动作行；不存在或不属于当前租户时为 null
     */
    PullTaskAccountAction selectByCommandId(@Param("commandId") String commandId);

    /** 任务结束时取消尚未提交协议命令的账号动作。 */
    int cancelPendingByTask(@Param("taskId") long taskId,
                            @Param("expectedStatus") int expectedStatus,
                            @Param("targetStatus") int targetStatus,
                            @Param("now") long now);

    /** 单群结束时取消该执行行尚未提交的账号动作。 */
    int cancelPendingByExecution(@Param("groupExecutionId") long groupExecutionId,
                                 @Param("expectedStatus") int expectedStatus,
                                 @Param("targetStatus") int targetStatus,
                                 @Param("now") long now);

    /** 把 Outbox 已取消但业务行已标为提交的动作收敛为取消。 */
    int cancelUnpublishedSubmitted(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("now") long now);
}
