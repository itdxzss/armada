package com.armada.task.mapper;

import com.armada.task.model.dto.PullTaskFilter;
import com.armada.task.model.dto.PullTaskDeleteCriteria;
import com.armada.task.model.dto.PullTaskDeleteRule;
import com.armada.task.model.dto.PullTaskExecutionSlotClaim;
import com.armada.task.model.dto.PullTaskLifecycleTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群任务公共主表数据访问层。 */
@Mapper
public interface PullTaskMapper {

    /**
     * 统计当前租户下符合条件的有效任务。
     *
     * @param filter SQL 筛选条件
     * @return 符合条件的任务数
     */
    long countPage(@Param("filter") PullTaskFilter filter);

    /**
     * 分页查询当前租户下符合条件的有效任务。
     *
     * @param filter SQL 筛选条件
     * @param offset SQL 偏移量
     * @param limit  最大返回行数
     * @return 按任务 ID 倒序的当前页
     */
    List<PullTask> selectPage(
            @Param("filter") PullTaskFilter filter,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 按任务类型和状态约束批量软删。
     *
     * @param ids       待删除任务 ID
     * @param deletedAt 软删时间(epoch 毫秒)
     * @return 实际软删行数
     */
    int batchSoftDeleteByCriteria(
            @Param("criteria") PullTaskDeleteCriteria criteria);

    /** 删除规则由 Java 枚举组装后传入 XML。 */
    default int batchSoftDeleteAllowed(List<Long> ids, long deletedAt) {
        return batchSoftDeleteByCriteria(new PullTaskDeleteCriteria(
                ids,
                List.of(
                        new PullTaskDeleteRule(
                                com.armada.task.model.enums.PullTaskType.GROUP_MARKETING,
                                List.of(com.armada.task.model.enums.PullTaskMarketingStatus.DRAFT.name())),
                        new PullTaskDeleteRule(
                                com.armada.task.model.enums.PullTaskType.STANDARD,
                                List.of(
                                        com.armada.task.model.enums.PullTaskStandardStatus.DRAFT.name(),
                                        com.armada.task.model.enums.PullTaskStandardStatus.WAIT_START.name(),
                                        com.armada.task.model.enums.PullTaskStandardStatus.COMPLETED.name(),
                                        com.armada.task.model.enums.PullTaskStandardStatus.ENDED.name()))),
                deletedAt));
    }

    /**
     * 按状态前置条件与乐观锁版本推进任务生命周期。
     *
     * <p>返回 0 表示任务不在允许的前置状态或版本已过期。这是人工操作幂等的落点
     * （ADR-0009）：重复提交因前置状态不满足返回 0，Service 据此把当前状态当作
     * 成功结果返回，不得原样重试。</p>
     *
     * @param id 任务 ID
     * @param fromStatus 允许的当前状态
     * @param toStatus 目标状态
     * @param expectedVersion 读取时拿到的版本号
     * @param startedAt 首次启动时间；传 null 时不写该列，已有值不被覆盖
     * @param finishedAt 终态时间；传 null 时不写该列，已有值不被覆盖
     * @param now 本次更新时间(epoch 毫秒)
     * @return 实际更新行数；1 表示成功推进，0 表示前置校验或乐观锁失败
     */
    int updateStatusWithVersion(@Param("id") long id,
                                @Param("fromStatus") String fromStatus,
                                @Param("toStatus") String toStatus,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("startedAt") Long startedAt,
                                @Param("finishedAt") Long finishedAt,
                                @Param("now") long now);

    /**
     * 以调用方给出的前置状态和版本号推进父任务，并同步生命周期展示字段。
     *
     * @param transition 状态、版本、原因与时间条件
     * @return 1 表示推进成功；0 表示状态或版本已经变化
     */
    int transitionLifecycle(@Param("transition") PullTaskLifecycleTransition transition);

    /**
     * 插入一条普通群链接草稿任务行。
     *
     * <p>{@code task_type} 固定 {@code STANDARD}、{@code mode} 固定 {@code NORMAL_LINK}、
     * {@code status} 固定 {@code DRAFT}、{@code config_json} 先写空对象；
     * {@code tenant_id} 由租户拦截器注入。草稿不进任务列表与任何聚合统计（ADR-0007）。</p>
     *
     * @param row 只需设置 taskName、operatorName、createdBy、createdAt、updatedAt；执行后回填 id
     * @return 插入行数
     */
    int insertDraftRow(PullTask row);

    /** 草稿初始业务值由 Java 领域值写入实体，XML 只负责持久化。 */
    default int insertDraft(PullTask row) {
        row.setTaskType(com.armada.task.model.enums.PullTaskType.STANDARD);
        row.setMode("NORMAL_LINK");
        row.setStatus(com.armada.task.model.enums.PullTaskStandardStatus.DRAFT.name());
        row.setGroupCount(0);
        row.setExpectedPullCount(0);
        row.setConfigJson("{}");
        return insertDraftRow(row);
    }

    /**
     * 取该创建人最新的一条普通群链接草稿。
     *
     * <p>同用户双击或多标签页可能漏出多条草稿，取最新一条容忍；这比为此加一条唯一索引迁移划算，
     * 遗留草稿是每用户常量级而不是随预览次数增长（ADR-0007）。</p>
     *
     * @param createdBy 创建人用户 ID
     * @return 最新草稿；没有时为 null，调用方负责包成 Optional
     */
    PullTask selectLatestDraft(
            @Param("createdBy") long createdBy,
            @Param("taskType") PullTaskType taskType,
            @Param("status") String status);

    /** 草稿类型和状态由 Java 枚举传入。 */
    default PullTask selectLatestDraftByCreator(long createdBy) {
        return selectLatestDraft(
                createdBy,
                com.armada.task.model.enums.PullTaskType.STANDARD,
                com.armada.task.model.enums.PullTaskStandardStatus.DRAFT.name());
    }

    /**
     * 把草稿提交为待启动任务。
     *
     * <p>状态迁移与任务名、备注、计数列在同一条带守卫的 UPDATE 里原子完成；
     * 拆成两条会让"状态已推进但计数未写"成为可观测中间态。
     * 重复提交返回 0 行，调用方据此走幂等分支而不是报错。</p>
     *
     * @param row 需设置 id、taskName、remark、groupCount、expectedPullCount
     * @param now 本次更新时间(epoch 毫秒)
     * @return 实际更新行数；1 表示提交成功，0 表示任务非标准草稿
     */
    int submitDraftTransition(
            @Param("row") PullTask row,
            @Param("expectedTaskType") PullTaskType expectedTaskType,
            @Param("expectedStatus") String expectedStatus,
            @Param("now") long now);

    /** 草稿提交的前置态和目标态由 Java 枚举传入。 */
    default int submitDraft(PullTask row, long now) {
        row.setStatus(PullTaskStandardStatus.WAIT_START.name());
        return submitDraftTransition(
                row,
                PullTaskType.STANDARD,
                PullTaskStandardStatus.DRAFT.name(),
                now);
    }

    /**
     * 读取任务生命周期字段，供 Service 在推进状态前取当前状态与版本号。
     *
     * <p>同时带上计数列（{@code groupCount}/{@code expectedPullCount}）与创建人
     * {@code createdBy}：普通群链接任务的提交冻结需要在同一次读取里完成归属校验、
     * 幂等判定与结果组装，多查一次没有必要。</p>
     *
     * @param id 任务 ID
     * @return 生命周期视图；任务不存在、已软删或不属于当前租户时为 null
     */
    PullTask selectLifecycle(@Param("id") long id);

    /**
     * 用父任务版本号与当前运行数原子竞争一个执行槽位。
     *
     * <p>返回 0 表示父任务状态/版本已变化或当前槽位已满。调用方随后仍需使用执行行自身的
     * 状态、版本和租约守卫把 {@code WAIT_START} 推进到 {@code EXECUTING}。</p>
     *
     * @param claim 父任务状态、版本及执行槽位条件
     * @return 1 表示取得槽位串行化令牌；0 表示条件不满足
     */
    int acquireExecutionSlot(@Param("claim") PullTaskExecutionSlotClaim claim);
}
