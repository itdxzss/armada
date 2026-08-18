package com.armada.group.mapper;

import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.vo.GroupBatchTaskItemRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群组列表批量刷新任务明细数据访问。 */
@Mapper
public interface GroupBatchTaskItemMapper {

    /**
     * 批量插入任务明细。
     *
     * @param rows 逐群明细;调用方保证已按 groupLinkId 去重
     * @return 影响行数
     */
    int batchInsert(@Param("rows") List<GroupBatchTaskItem> rows);

    /**
     * 读取任务的全部明细,供进度弹窗展示。
     *
     * @param taskId 任务 ID
     * @return 按 ID 升序的明细列表
     */
    List<GroupBatchTaskItem> selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 读取任务明细的展示行,联表补齐执行账号号码。
     *
     * @param taskId 任务 ID
     * @return 按 ID 升序的展示行
     */
    List<GroupBatchTaskItemRow> selectDetailRowsByTaskId(@Param("taskId") Long taskId);

    /**
     * 读取任务中尚未终结的明细,供执行器领取。
     *
     * @param taskId 任务 ID
     * @param pendingStatus 待执行稳定码
     * @param limit 单次领取上限
     * @return 按 ID 升序的待执行明细
     */
    List<GroupBatchTaskItem> selectPending(@Param("taskId") Long taskId,
                                           @Param("pendingStatus") int pendingStatus,
                                           @Param("limit") int limit);

    /** Outbox 写入后把待执行项 CAS 为等待协议结果。 */
    int markWaitingResult(@Param("row") GroupBatchTaskItem row,
                          @Param("pendingStatus") int pendingStatus,
                          @Param("waitingStatus") int waitingStatus);

    /** 命令事实落库后按 commandId 幂等累计完成 scope。 */
    int markScopeCompleted(@Param("commandId") String commandId,
                           @Param("scopeMask") int scopeMask,
                           @Param("observedAt") long observedAt);

    /** 按当前 commandId 读取等待结算的批量明细。 */
    GroupBatchTaskItem selectByCurrentCommandId(@Param("tenantId") Long tenantId,
                                                @Param("commandId") String commandId);

    /** 仅供 INVALID_PAYLOAD 结算按全局 commandId 找回明细，避免非法 tenant 字段造成超时。 */
    @InterceptorIgnore(tenantLine = "true")
    GroupBatchTaskItem selectByCurrentCommandIdUnscoped(@Param("commandId") String commandId);

    /** 当前命令失败后 CAS 回待执行，保留已成功 scope 并推进候选。 */
    int resetCurrentCommandForRetry(@Param("row") GroupBatchTaskItem row,
                                    @Param("waitingStatus") int waitingStatus,
                                    @Param("pendingStatus") int pendingStatus);

    /** 按 commandId CAS 终结等待中的明细。 */
    int settleCurrentCommand(@Param("row") GroupBatchTaskItem row,
                             @Param("waitingStatus") int waitingStatus);

    /** 读取一个批量任务中已超时的等待结果明细。 */
    List<GroupBatchTaskItem> selectExpiredSnapshots(@Param("taskId") Long taskId,
                                                    @Param("waitingStatus") int waitingStatus,
                                                    @Param("now") long now,
                                                    @Param("limit") int limit);

    /**
     * 终结一条明细。
     *
     * <p>只允许从待执行推进到终态,重复终结返回 0 行,避免执行器重入时把同一项计入两次汇总。</p>
     *
     * @param row 携带 id、status、accountId、groupJid、errorCode、description、operatedAt 的明细
     * @param pendingStatus 待执行稳定码
     * @return 影响行数;已终结时为 0
     */
    int finishItem(@Param("row") GroupBatchTaskItem row, @Param("pendingStatus") int pendingStatus);

    /**
     * 取消任务中尚未终结的明细。
     *
     * <p>待执行及等待只读快照结果的项均可取消；已成功/已失败的既有结果不能覆盖。</p>
     *
     * @param taskId 任务 ID
     * @param canceledStatus 已取消稳定码
     * @param pendingStatus 待执行稳定码
     * @param waitingStatus 等待协议结果稳定码
     * @param now 取消时间(epoch 毫秒)
     * @return 实际取消的明细数
     */
    int cancelPending(@Param("taskId") Long taskId,
                      @Param("canceledStatus") int canceledStatus,
                      @Param("pendingStatus") int pendingStatus,
                      @Param("waitingStatus") int waitingStatus,
                      @Param("now") long now);
}
