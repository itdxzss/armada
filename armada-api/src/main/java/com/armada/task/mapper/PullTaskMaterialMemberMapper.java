package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskMaterialMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 料子号码与逐号码结果数据访问层。 */
@Mapper
public interface PullTaskMaterialMemberMapper {

    /**
     * 解析 TXT 后批量写入去重后的有效号码。
     *
     * @param rows 料子成员，按 memberSeq 升序
     * @return 新增行数
     */
    int batchInsert(@Param("rows") List<PullTaskMaterialMember> rows);

    /**
     * 读取执行行的全部料子，按 memberSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @return 料子列表
     */
    List<PullTaskMaterialMember> selectByExecution(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 删除某条执行行下的全部料子成员。
     *
     * <p>只在创建页的"单行移除"与"清除全部"里使用；执行行是否允许删除由
     * {@code PullTaskGroupExecutionMapper#deleteDraftRow} 的状态守卫把关，
     * 本方法不重复判断执行行状态。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @return 实际删除行数
     */
    int deleteByExecution(@Param("groupExecutionId") long groupExecutionId);

    /**
     * 取下一批尚未消费的料子。
     *
     * <p>{@code pull_call_id IS NULL} 即"未消费"，这就是料子游标本身，
     * 执行行上不再单独存游标列。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param limit 本次调用需要的料子人数
     * @return 未消费料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectUnconsumed(@Param("groupExecutionId") long groupExecutionId,
                                                  @Param("limit") int limit);

    /**
     * 把选中的料子绑定到一次拉人调用。
     *
     * <p>只更新仍未消费的行；返回行数小于入参数量说明有并发消费，调用方必须放弃
     * 本次调用并重新取料，不得按原数量提交协议命令。</p>
     *
     * @param ids 料子 ID
     * @param pullCallId 拉人调用 ID
     * @param now 更新时间(epoch 毫秒)
     * @return 实际绑定行数
     */
    int assignToCall(@Param("ids") List<Long> ids,
                     @Param("pullCallId") long pullCallId,
                     @Param("now") long now);

    /**
     * 回写单个号码的入群结果。
     *
     * @param id 料子 ID
     * @param pullStatus 入群结果，取值见 PullTaskMaterialPullStatus
     * @param reasonCode 失败原因码
     * @param reasonMessage 失败原因描述(已脱敏)
     * @param waJid 成功入群后的成员 JID
     * @param now 回写时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int writeBackPullResult(@Param("id") long id,
                            @Param("pullStatus") int pullStatus,
                            @Param("reasonCode") String reasonCode,
                            @Param("reasonMessage") String reasonMessage,
                            @Param("waJid") String waJid,
                            @Param("now") long now);

    /**
     * 取本执行行待提权的料子：带 A/a 标识、已成功入群、尚未提交提权。
     *
     * @param groupExecutionId 执行行 ID
     * @return 待提权料子，按 memberSeq 升序
     */
    List<PullTaskMaterialMember> selectPendingAdmin(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 标记提权命令已提交。
     *
     * @param id 料子 ID
     * @param adminCommandId 提权协议命令 ID
     * @param now 提交时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markAdminSubmitted(@Param("id") long id,
                           @Param("adminCommandId") String adminCommandId,
                           @Param("now") long now);

    /**
     * 提权回调按命令 ID 定位料子行。
     *
     * @param adminCommandId 提权协议命令 ID
     * @return 料子行；不存在或不属于当前租户时为 null
     */
    PullTaskMaterialMember selectByAdminCommandId(
            @Param("adminCommandId") String adminCommandId);
}
