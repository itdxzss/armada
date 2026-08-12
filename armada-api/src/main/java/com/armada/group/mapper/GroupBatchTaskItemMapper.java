package com.armada.group.mapper;

import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.vo.GroupBatchTaskItemRow;
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
}
