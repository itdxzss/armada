package com.armada.group.mapper;

import com.armada.group.model.entity.GroupBatchTask;
import com.armada.shared.security.DataScope;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群组列表批量刷新任务主表数据访问。 */
@Mapper
public interface GroupBatchTaskMapper {

    /**
     * 插入批量任务主记录。
     *
     * @param row 任务主记录;回填自增主键
     * @return 影响行数
     */
    int insert(GroupBatchTask row);

    /**
     * 按主键读取批量任务。
     *
     * @param id 任务 ID
     * @return 任务主记录;不存在时为 null
     */
    GroupBatchTask selectById(@Param("id") Long id, @Param("scope") DataScope scope);

    /**
     * 在已恢复任务租户的后台执行链读取任务根，不接受 HTTP 用户范围。
     *
     * @param id 任务 ID
     * @return 任务主记录；不存在时为 null
     */
    GroupBatchTask selectByIdForExecution(@Param("id") Long id);

    /**
     * 按前端幂等键读取当前租户已有任务。
     *
     * @param requestId 前端幂等键
     * @param ownerUserId 当前操作者 owner；管理员也只能复用自己创建的任务
     * @return 已有任务;不存在时为 null
     */
    GroupBatchTask selectByRequestId(@Param("requestId") String requestId,
                                     @Param("ownerUserId") Long ownerUserId);

    /**
     * 跨租户扫描仍需推进的批量任务,供调度器领取。
     *
     * <p>调度线程没有租户上下文,必须绕过租户拦截器,否则会被注入哨兵租户条件、一条都扫不到
     * (且不报错,只是静默空转)。调度器随后按每个任务的 tenantId 切上下文再查明细。</p>
     *
     * @param statuses 可推进的任务主状态稳定码
     * @param limit 单轮扫描上限
     * @return 按 ID 升序的任务
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupBatchTask> selectRunnableTasks(@Param("statuses") List<Integer> statuses,
                                             @Param("limit") int limit);

    /**
     * 逐项终结时原子递增汇总计数,并在最后一项终结时落完成态。
     *
     * <p>必须由每项独立事务调用,否则整批一个事务会让前端轮询到的进度从 0% 直接跳到 100%。
     * 计数一律走 SQL 自增而非读改写,多实例并发终结同一任务时不会丢计数。</p>
     *
     * @param taskId 任务 ID
     * @param success 该项是否成功
     * @param completedStatus 完成态稳定码
     * @param runningStatus 运行中稳定码
     * @param now 该项结束时间(epoch 毫秒)
     * @return 影响行数
     */
    int applyItemOutcome(@Param("taskId") Long taskId,
                         @Param("success") boolean success,
                         @Param("completedStatus") int completedStatus,
                         @Param("runningStatus") int runningStatus,
                         @Param("now") long now);

    /**
     * 把仍可推进的任务落成取消态。
     *
     * <p>带 status 白名单:已完成/已失败的任务不能被改写，重复取消返回 0 行。</p>
     *
     * @param taskId 任务 ID
     * @param canceledStatus 已取消稳定码
     * @param runnableStatuses 允许取消的当前状态稳定码
     * @param now 取消时间(epoch 毫秒)
     * @return 影响行数;已是终态时为 0
     */
    int cancelIfRunnable(@Param("taskId") Long taskId,
                         @Param("canceledStatus") int canceledStatus,
                         @Param("runnableStatuses") List<Integer> runnableStatuses,
                         @Param("scope") DataScope scope,
                         @Param("now") long now);

    /** 将历史空 owner 的可运行任务终止为失败，防止后台越权执行。 */
    int failUnownedIfRunnable(@Param("taskId") Long taskId,
                              @Param("failedStatus") int failedStatus,
                              @Param("runnableStatuses") List<Integer> runnableStatuses,
                              @Param("now") long now);

    /**
     * 跨租户读取任务主状态,供调度线程判断是否已被取消。
     *
     * <p>与 selectRunnableTasks 同理绕过租户拦截器:调度线程虽已切租户上下文，但取消可能来自
     * 另一个实例，这里只读一个状态码，不需要租户过滤。</p>
     *
     * @param taskId 任务 ID
     * @return 状态稳定码;任务不存在时为 null
     */
    @InterceptorIgnore(tenantLine = "true")
    Integer selectStatusById(@Param("taskId") Long taskId);

    /** 首条明细成功写入 Outbox 后把主任务从 PENDING 推进为 RUNNING。 */
    int markRunning(@Param("taskId") Long taskId,
                    @Param("pendingStatus") int pendingStatus,
                    @Param("runningStatus") int runningStatus);
}
