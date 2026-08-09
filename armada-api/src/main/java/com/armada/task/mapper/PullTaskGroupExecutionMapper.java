package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionAbandon;
import com.armada.task.model.dto.PullTaskExecutionManualChange;
import com.armada.task.model.dto.PullTaskExecutionManualTransition;
import com.armada.task.model.dto.PullTaskExecutionTerminalTransition;
import com.armada.task.model.dto.PullTaskManagerSupplementTransition;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskResourceSupplementTransition;
import com.armada.task.model.dto.PullTaskUnknownReconciliationCriteria;
import com.armada.task.model.dto.PullTaskStickyPullerInvalidation;
import com.armada.task.model.dto.PullTaskStickyPullerTransition;
import com.armada.task.model.dto.PullTaskPullWaveDispatchAdvance;
import com.armada.task.model.dto.PullTaskPullWaveCollectionWake;
import com.armada.task.model.dto.PullTaskPullWaveSettlementAdvance;
import com.armada.task.model.dto.PullTaskMemberQueryWake;
import com.armada.task.model.dto.PullTaskMemberQueryDefer;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接执行行数据访问层。 */
@Mapper
public interface PullTaskGroupExecutionMapper {

    /**
     * 创建页生成匹配计划时写入草稿执行行。
     *
     * <p>草稿行 {@code execution_status=0}，生成列 {@code link_occupancy_key} 为 NULL，
     * 因此不同用户的草稿可以同时持有同一条群链接（ADR-0007）。</p>
     *
     * <p>Java 入口把状态、阶段、暂停标记、游标、调度时间和版本初始化为草稿值，
     * XML 只负责持久化明确参数，不持有业务状态机常量。</p>
     *
     * @param row 草稿执行行；写入后回填 id
     * @return 新增行数
     */
    int insertDraftInitialized(PullTaskGroupExecution row);

    /** 初始化草稿行后写入，避免 Mapper XML 固化业务值。 */
    default int insertDraft(PullTaskGroupExecution row) {
        row.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
        row.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        row.setManualPaused(0);
        row.setNextManagerIndex(0);
        row.setNextPullerIndex(0);
        row.setPullerAssignmentSeq(0L);
        row.setNextRunAt(0L);
        row.setVersion(1);
        return insertDraftInitialized(row);
    }

    /**
     * 读取任务的全部执行行，按 seq 升序。
     *
     * @param taskId 拉群任务 ID
     * @return 执行行列表
     */
    List<PullTaskGroupExecution> selectByTaskId(@Param("taskId") long taskId);

    /**
     * 按主键读取执行行。
     *
     * @param id 执行行 ID
     * @return 当前租户内的执行行；不存在时为 null
     */
    PullTaskGroupExecution selectById(@Param("id") long id);

    /**
     * 按群入口读取当前租户内仍可终止的普通拉群执行行。
     *
     * @param groupLinkId 群入口 ID
     * @param executionStatuses 可终止执行状态
     * @param parentTaskType 父任务类型
     * @param parentTaskMode 父任务模式
     * @param parentStatuses 可处理父任务状态
     * @return 符合条件的执行行，按主键升序
     */
    List<PullTaskGroupExecution> selectActiveByGroupLinkId(
            @Param("groupLinkId") long groupLinkId,
            @Param("executionStatuses") List<Integer> executionStatuses,
            @Param("parentTaskType") String parentTaskType,
            @Param("parentTaskMode") String parentTaskMode,
            @Param("parentStatuses") List<String> parentStatuses);

    /**
     * 删除任务下尚未冻结的草稿执行行，支撑创建页的"清除全部"。
     *
     * @param taskId 拉群任务 ID
     * @return 删除行数
     */
    int deleteDraftByTaskId(
            @Param("taskId") long taskId,
            @Param("expectedExecutionStatus") int expectedExecutionStatus);

    /** 草稿兼容入口；前置状态由 Java 枚举传入 XML。 */
    default int deleteDraftByTaskId(long taskId) {
        return deleteDraftByTaskId(taskId, PullTaskExecutionStatus.DRAFT.code());
    }

    /**
     * 删除草稿任务下的单条执行行。
     *
     * <p>带 {@code execution_status = 0} 守卫，已冻结的执行行删不掉。
     * 调用方必须先删该行的料子成员：若本方法返回 0，事务回滚会把料子恢复。</p>
     *
     * @param taskId 草稿任务 ID
     * @param rowId  执行行 ID
     * @return 实际删除行数；0 表示行不存在、不属于该任务或已冻结
     */
    int deleteDraftRow(
            @Param("taskId") long taskId,
            @Param("rowId") long rowId,
            @Param("expectedExecutionStatus") int expectedExecutionStatus);

    /** 草稿兼容入口；前置状态由 Java 枚举传入 XML。 */
    default int deleteDraftRow(long taskId, long rowId) {
        return deleteDraftRow(taskId, rowId, PullTaskExecutionStatus.DRAFT.code());
    }

    /**
     * 查出这批链接里已被本租户运行中任务占用的部分。
     *
     * <p>占用口径与生成列 {@code link_occupancy_key} 一致：{@code execution_status} 为
     * 1（待启动）、2（运行中）、3（暂停）时占用，草稿与终态不占用。这是创建页的软提示，
     * 硬互斥由唯一键在提交时承担。</p>
     *
     * <p><b>调用方必须保证 links 非空</b>：空集合会让 {@code foreach} 生成非法 SQL。</p>
     *
     * @param links 待检查的归一化链接，非空
     * @return 已被占用的归一化链接；无占用时为空列表
     */
    List<String> selectOccupiedLinks(
            @Param("links") List<String> links,
            @Param("executionStatuses") List<Integer> executionStatuses);

    /** 链接占用兼容入口；占用状态集合由 Java 枚举传入 XML。 */
    default List<String> selectOccupiedLinks(List<String> links) {
        return selectOccupiedLinks(
                links,
                List.of(
                        PullTaskExecutionStatus.WAIT_START.code(),
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStatus.WAIT_RESOURCE.code()));
    }

    /**
     * 回填执行行的群入口 ID。
     *
     * <p>只在提交冻结的事务里调用，带 {@code execution_status = 0} 守卫保证不改已冻结行。</p>
     *
     * @param id          执行行 ID
     * @param groupLinkId 群入口 ID
     * @param now         更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int updateGroupLinkId(
            @Param("id") long id,
            @Param("groupLinkId") long groupLinkId,
            @Param("expectedExecutionStatus") int expectedExecutionStatus,
            @Param("now") long now);

    /** 草稿兼容入口；前置状态由 Java 枚举传入 XML。 */
    default int updateGroupLinkId(long id, long groupLinkId, long now) {
        return updateGroupLinkId(
                id, groupLinkId, PullTaskExecutionStatus.DRAFT.code(), now);
    }

    /**
     * 任务由草稿冻结为待启动时，把本任务的草稿执行行整体推进为待启动。
     *
     * <p>推进后生成列 {@code link_occupancy_key} 取到链接值，占用随之生效；
     * 若同一链接已被另一个在跑的任务占用，数据库唯一键会抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它翻译成
     * 面向运营的"群链接已被占用"业务异常。</p>
     *
     * @param taskId 拉群任务 ID
     * @param now 冻结时间(epoch 毫秒)
     * @return 实际冻结行数
     */
    int freezeDraftRows(
            @Param("taskId") long taskId,
            @Param("expectedExecutionStatus") int expectedExecutionStatus,
            @Param("targetExecutionStatus") int targetExecutionStatus,
            @Param("now") long now);

    /** 草稿冻结兼容入口；迁移两端状态由 Java 枚举传入 XML。 */
    default int freezeDraftRows(long taskId, long now) {
        return freezeDraftRows(
                taskId,
                PullTaskExecutionStatus.DRAFT.code(),
                PullTaskExecutionStatus.WAIT_START.code(),
                now);
    }

    /**
     * 调度器跨租户抢占到期的执行行。
     *
     * <p>后台调度线程没有租户上下文（{@code MyBatisConfig} 无上下文时 fail-closed
     * 回退 -1），因此这里忽略租户拦截，并只走不带租户前缀的
     * {@code idx_pull_task_execution_dispatch} 索引。</p>
     *
     * @param criteria 抢占范围、状态机条件与租约参数
     * @return 实际抢占行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int claimDue(@Param("criteria") PullTaskExecutionClaimCriteria criteria);

    /**
     * 读取本实例当前持有且租约仍未过期的执行行。
     *
     * <p>只按 {@code lock_owner} 过滤是不够的：租约静默过期后，若尚未有别的实例把它抢走，
     * 这里仍会把该行当作"本实例持有"返回。因此额外要求 {@code lock_expires_at > now}，
     * 让过期的持有在被真正抢占之前就已经对原持有者不可见。</p>
     *
     * @param lockOwner 抢占实例标识
     * @param now 当前时间(epoch 毫秒)
     * @return 该实例当前持有且租约未过期的执行行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> selectClaimed(@Param("lockOwner") String lockOwner,
                                               @Param("now") long now);

    /**
     * 跨租户读取需要查询/回调收敛的执行行；业务类型和状态全部由 Java 条件传入。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> selectUnknownResultCandidates(
            @Param("criteria") PullTaskUnknownReconciliationCriteria criteria);

    /**
     * 把本实例持有租约的待启动行推进为执行中。
     *
     * @param row 需携带 id、version、lockOwner、startedAt、updatedAt
     * @return 1 表示成功；0 表示状态、版本、租约或租户已变化
     */
    int startClaimed(
            @Param("row") PullTaskGroupExecution row,
            @Param("expectedExecutionStatus") int expectedExecutionStatus,
            @Param("expectedStage") int expectedStage,
            @Param("targetExecutionStatus") int targetExecutionStatus);

    /** 兼容入口；状态机条件由 Java 枚举传入 XML。 */
    default int startClaimed(PullTaskGroupExecution row) {
        return startClaimed(
                row,
                PullTaskExecutionStatus.WAIT_START.code(),
                PullTaskExecutionStage.LINK_VALIDATION.code(),
                PullTaskExecutionStatus.EXECUTING.code());
    }

    /**
     * 按本实例持有的有效租约原子写入当前阶段结果并释放租约。
     *
     * @param row 需携带目标状态、阶段、原因、时间、id、version 与 lockOwner
     * @param expectedExecutionStatus 更新前必须匹配的执行状态
     * @param expectedStage 更新前必须匹配的业务阶段
     * @return 1 表示成功；0 表示版本、租约或租户已变化
     */
    int transitionClaimed(@Param("row") PullTaskGroupExecution row,
                          @Param("expectedExecutionStatus") int expectedExecutionStatus,
                          @Param("expectedStage") int expectedStage);

    /** 执行中阶段的兼容入口；期望状态仍由 Java 显式传给 XML。 */
    default int transitionClaimed(PullTaskGroupExecution row, int expectedStage) {
        return transitionClaimed(
                row, PullTaskExecutionStatus.EXECUTING.code(), expectedStage);
    }

    /**
     * 用乐观锁推进执行行的检查点。
     *
     * <p>本方法未加 {@code @InterceptorIgnore}，因此会被注入 {@code AND tenant_id = ?}。
     * 调用方必须在调用前把 {@link com.armada.shared.tenant.TenantContext} 设置为该行所属的
     * 租户——通过 {@code claimDue}/{@code selectClaimed} 这两个跨租户方法拿到的行不自带租户
     * 上下文，调度器必须先从行内 {@code tenantId} 恢复上下文再调用本方法。若上下文缺失或与
     * 该行租户不一致，注入的条件会退化为哨兵 -1，匹配不到任何行。</p>
     *
     * @param id 执行行 ID
     * @param expectedVersion 读取时拿到的版本号
     * @param nextManagerIndex 新的管理账号轮询游标
     * @param nextPullerIndex 新的下一拉手角色序号游标
     * @param stage 新的业务阶段
     * @param nextRunAt 下次可调度时间(epoch 毫秒)
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数；0 表示版本已过期，或调用时缺失/错配租户上下文
     */
    int updateCheckpoint(@Param("id") long id,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("nextManagerIndex") Integer nextManagerIndex,
                         @Param("nextPullerIndex") Integer nextPullerIndex,
                         @Param("stage") Integer stage,
                         @Param("nextRunAt") long nextRunAt,
                         @Param("now") long now);

    /**
     * 以版本和有效租约 CAS 绑定首次创建的活动拉人波次。
     *
     * @param id 执行行 ID
     * @param expectedVersion 读取时版本号
     * @param lockOwner 当前租约持有者
     * @param pullWaveId 新活动波次 ID
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int bindActivePullWave(
            @Param("id") long id,
            @Param("expectedVersion") int expectedVersion,
            @Param("lockOwner") String lockOwner,
            @Param("pullWaveId") long pullWaveId,
            @Param("now") long now);

    /**
     * 一次调用提交后推进执行行派发时钟并释放当前租约。
     *
     * @param advance 波次与执行行共享的派发推进参数
     * @return 实际更新行数
     */
    int advancePullWaveDispatch(
            @Param("advance") PullTaskPullWaveDispatchAdvance advance);

    /** 波次结算后替换活动指针、推进阶段并释放当前租约。 */
    int completePullWaveSettlement(
            @Param("advance") PullTaskPullWaveSettlementAdvance advance);

    /** 只唤醒身份仍匹配的收集态拉人执行行。 */
    int wakePullWaveCollection(
            @Param("wake") PullTaskPullWaveCollectionWake wake);

    /** 成员查询完成后只唤醒仍在原阶段、且已经释放租约的执行行。 */
    int wakeForMemberQuery(@Param("wake") PullTaskMemberQueryWake wake);

    /** 成员查询尚未完成时，以当前有效租约释放执行行直到查询截止时间。 */
    int deferForMemberQuery(@Param("defer") PullTaskMemberQueryDefer defer);

    /** 以当前拉手身份和代际 CAS 分配下一粘性拉手。 */
    int transitionStickyPuller(
            @Param("transition") PullTaskStickyPullerTransition transition);

    /** 身份和代际都匹配时清空当前拉手，代际保持不变。 */
    int clearStickyPuller(
            @Param("invalidation") PullTaskStickyPullerInvalidation invalidation);

    /** 向任务下全部非终态执行行传播或解除人工暂停标记。 */
    int applyManualChange(@Param("change") PullTaskExecutionManualChange change);

    /** 任务结束时把全部非终态执行行推进为放弃终态。 */
    int abandonByTask(@Param("change") PullTaskExecutionAbandon change);

    /** 以执行状态、人工标记和版本号 CAS 暂停或恢复单群。 */
    int transitionManual(
            @Param("transition") PullTaskExecutionManualTransition transition);

    /** 以执行状态和版本号 CAS 把单群推进为不可恢复终态。 */
    int transitionTerminal(
            @Param("transition") PullTaskExecutionTerminalTransition transition);

    /** 以资源等待类型、阶段和版本 CAS 激活已确认的补充管理员指令。 */
    int activateManagerSupplement(
            @Param("transition") PullTaskManagerSupplementTransition transition);

    /** 以等待类型、阶段和版本 CAS 激活拉手或站台补充指令。 */
    int activateResourceSupplement(
            @Param("transition") PullTaskResourceSupplementTransition transition);

    /** 以版本、当前状态和阶段 CAS 应用管理员踩链接异步结果，不要求调度租约。 */
    int transitionManagerJoinResult(
            @Param("transition") PullTaskManagerJoinResultTransition transition);

    /** 仅在执行行未被调度器占用时，以状态、阶段和版本 CAS 应用协议结果。 */
    int transitionProtocolResult(
            @Param("transition") PullTaskExecutionResultTransition transition);

    /**
     * 释放本实例持有的调度锁。
     *
     * @param id 执行行 ID
     * @param lockOwner 抢占实例标识
     * @param now 释放时间(epoch 毫秒)，写入 {@code updated_at}
     * @return 实际释放行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int releaseLock(@Param("id") long id, @Param("lockOwner") String lockOwner, @Param("now") long now);
}
