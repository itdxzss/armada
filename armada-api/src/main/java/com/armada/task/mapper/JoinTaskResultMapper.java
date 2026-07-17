package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.model.entity.JoinTaskResult;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 进群任务明细数据访问层，映射 {@code join_task_result} 表（账号×链接计划行）。
 *
 * <p>plain {@code @Mapper}，SQL 全部写在 XML，不用注解 SQL。
 * tenant_id 由租户拦截器自动注入，所有方法禁止手写 tenant_id 条件。</p>
 *
 * <p>引擎列（group_jid/is_admin/promoted_at）由引擎切片另行更新，此处不涉及。</p>
 */
@Mapper
public interface JoinTaskResultMapper {

    /**
     * 批量插入计划行。调用方须先为每行设置 {@code joinTaskId}、{@code createdAt}、{@code updatedAt}。
     * 引擎列（group_jid/is_admin/promoted_at）使用 DB 默认值（''/0/NULL）。
     *
     * @param rows 待插入的计划行列表，不得为空
     * @return 实际插入行数
     */
    int insertResults(@Param("rows") List<JoinTaskResult> rows);

    /**
     * 查询指定任务的全部计划行，按 id 升序（保持建任务时的分配顺序）。
     *
     * @param joinTaskId 进群任务 ID
     * @return 该任务下所有计划行；任务不存在或无行时返回空列表
     */
    List<JoinTaskResult> selectResultsByTask(@Param("joinTaskId") Long joinTaskId);

    /**
     * 查询待执行计划行，按 id 升序。
     *
     * @param joinTaskId 进群任务 ID
     * @return 待执行行列表
     */
    List<JoinTaskResult> selectPendingResultsByTask(@Param("joinTaskId") Long joinTaskId);

    /**
     * 把单行标记为成功并回填群 JID。
     *
     * @param id        明细行 ID
     * @param groupJid  协议层返回的群 JID
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 受影响行数
     */
    int updateResultSuccess(@Param("id") Long id,
                            @Param("groupJid") String groupJid,
                            @Param("updatedAt") long updatedAt);

    /**
     * 把单行标记为失败并写失败原因。
     *
     * @param id        明细行 ID
     * @param reason    失败原因码或摘要
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 受影响行数
     */
    int updateResultFailed(@Param("id") Long id,
                           @Param("reason") String reason,
                           @Param("updatedAt") long updatedAt);

    /**
     * 物理删除指定任务的全部计划行，供编辑重建时清空旧行使用。
     *
     * @param joinTaskId 进群任务 ID
     * @return 被删除的行数
     */
    int deleteResultsByTask(@Param("joinTaskId") Long joinTaskId);

    /**
     * 跨租户预扫描已到期的 WAITING 明细，不在此阶段加锁。
     *
     * <p>该方法显式绕过租户插件，只返回 tenantId/resultId 最小引用；调用方必须按租户分组，并在设置
     * TenantContext 后调用 {@link #selectDueForUpdate(Long, List, long)} 复核。</p>
     *
     * @param now 到期判断时间（epoch 毫秒）
     * @param limit 单轮最大候选数
     * @return 按 next_execute_at、id 升序排列的跨租户候选
     */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskDispatchCandidate> selectDueCandidates(
            @Param("now") long now,
            @Param("limit") int limit);

    /**
     * 在当前租户短事务内复核候选，并使用 {@code FOR UPDATE SKIP LOCKED} 抢占可派发行。
     *
     * <p>除到期条件外，还排除同任务同账号已有 SUBMITTED 行，形成数据库级账号 lane 闸门。</p>
     *
     * <p>锁行尾句会被租户拦截器重写成非法 MySQL 语序，因此本方法按项目已有锁行
     * Mapper 惯例关闭拦截器，并显式使用调度协调器分组后传入的 tenantId。</p>
     *
     * @param tenantId 当前分组租户 ID，必须与当前 TenantContext 一致
     * @param ids 预扫描得到的候选明细 ID
     * @param now 到期判断时间（epoch 毫秒）
     * @return 当前事务成功锁定的明细；已被其它实例处理或锁定的行不返回
     */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskResult> selectDueForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("ids") List<Long> ids,
            @Param("now") long now);

    /**
     * 启动任务时激活每个账号 ID 最小的首条 PENDING 明细。
     *
     * <p>只给每个账号一行设置 next_execute_at，其余行保持空值，因此任务包含多少账号就能同时形成
     * 多少条独立 lane，而不是由固定线程数限制。</p>
     *
     * @param joinTaskId 进群任务 ID
     * @param now 首批明细允许派发时间（epoch 毫秒）
     * @return 被激活的账号 lane 数
     */
    int activateFirstPendingPerAccount(
            @Param("joinTaskId") Long joinTaskId,
            @Param("now") long now);

    /**
     * 锁定仍等待指定命令结果的当前尝试。
     *
     * @param id 进群明细 ID
     * @param commandId 当前 outbox 命令 ID
     * @param attemptNo 当前业务尝试序号
     * @return 完全匹配且仍为 PENDING+SUBMITTED 的明细；重复或迟到事件返回 null
     */
    JoinTaskResult selectSubmittedForUpdate(
            @Param("id") Long id,
            @Param("commandId") String commandId,
            @Param("attemptNo") int attemptNo);

    /**
     * 把已写入 outbox 的到期明细切换为 SUBMITTED。
     *
     * @param id 进群明细 ID
     * @param commandId 本次 outbox 命令 ID
     * @param attemptNo 本次业务尝试序号
     * @param now 状态更新时间（epoch 毫秒），同时用于再次校验明细已经到期
     * @return 1 表示迁移成功；0 表示状态或到期条件已变化
     */
    int markSubmitted(
            @Param("id") Long id,
            @Param("commandId") String commandId,
            @Param("attemptNo") int attemptNo,
            @Param("now") long now);

    /**
     * 把当前 SUBMITTED 尝试恢复为 WAITING 并设置下次执行时间。
     *
     * <p>command_id 被清空但 attempt_no 保留，下一次派发会在原序号上递增。</p>
     *
     * @param id 进群明细 ID
     * @param reason 本次失败原因码
     * @param nextExecuteAt 按任务随机间隔计算的下次允许执行时间
     * @param now 状态更新时间（epoch 毫秒）
     * @return 1 表示重新排期成功；0 表示当前行已不在 SUBMITTED
     */
    int markRetry(
            @Param("id") Long id,
            @Param("reason") String reason,
            @Param("nextExecuteAt") long nextExecuteAt,
            @Param("now") long now);

    /**
     * 把当前 SUBMITTED 尝试收敛为进群成功终态。
     *
     * @param id 进群明细 ID
     * @param groupJid 协议层返回的 WhatsApp 群 JID；可为空串
     * @param now 状态更新时间（epoch 毫秒）
     * @return 1 表示迁移成功；0 表示当前行已被其它结果处理
     */
    int markTerminalSuccess(
            @Param("id") Long id,
            @Param("groupJid") String groupJid,
            @Param("now") long now);

    /**
     * 把 WAITING 前置失败或 SUBMITTED 执行失败收敛为业务失败终态。
     *
     * @param id 进群明细 ID
     * @param reason 稳定失败原因码
     * @param now 状态更新时间（epoch 毫秒）
     * @return 1 表示迁移成功；0 表示当前行已进入其它状态
     */
    int markTerminalFailure(
            @Param("id") Long id,
            @Param("reason") String reason,
            @Param("now") long now);

    /**
     * 激活同任务同账号当前行之后 ID 最小的下一条 WAITING 明细。
     *
     * <p>只有当前行已终结时由状态机调用；下一行的时间以当前完成时间为基准计算，而不是以上次计划
     * 时间为基准，确保真实的账号操作间隔满足业务配置。</p>
     *
     * @param joinTaskId 进群任务 ID
     * @param accountId 当前账号 ID
     * @param afterId 已终结的当前明细 ID
     * @param nextExecuteAt 下一行允许执行时间（epoch 毫秒）
     * @param now 状态更新时间（epoch 毫秒）
     * @return 1 表示激活下一行；0 表示该账号已无后续明细
     */
    int activateNextPending(
            @Param("joinTaskId") Long joinTaskId,
            @Param("accountId") Long accountId,
            @Param("afterId") Long afterId,
            @Param("nextExecuteAt") long nextExecuteAt,
            @Param("now") long now);

    /**
     * 跨租户扫描 outbox 已进入 DEAD 且业务仍在等待的进群尝试。
     *
     * <p>查询同时匹配 tenant、聚合类型、明细 ID 和 commandId，避免把同一明细的历史 DEAD 命令误认
     * 为当前尝试。调用方仍需进入租户事务再次锁定复核。</p>
     *
     * @param deadStatus outbox DEAD 状态码
     * @param limit 单轮最大候选数
     * @return 按 outbox 更新时间排列的待收敛传输失败候选
     */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskDeadCommandCandidate> selectDeadSubmittedCandidates(
            @Param("deadStatus") int deadStatus,
            @Param("limit") int limit);
}
