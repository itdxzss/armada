package com.armada.group.mapper;

import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 历史群拉人成员明细数据访问。 */
@Mapper
public interface HistoricalGroupPullMemberMapper {

    /** 批量写入成员初始状态；同一执行中的手机号只能出现一次。 */
    int batchInsert(@Param("rows") List<HistoricalGroupPullMember> rows);

    /** 按租户和主键查询成员快照。 */
    HistoricalGroupPullMember selectByTenantAndId(@Param("tenantId") Long tenantId,
                                                   @Param("id") Long id);

    /** 按输入行号稳定返回一次执行的成员。 */
    List<HistoricalGroupPullMember> selectOrderedByExecutionId(@Param("executionId") Long executionId);

    /** 仅把仍待处理的联系方式检查更新为终态。 */
    int updateContactResultIfPending(@Param("id") Long id,
                                     @Param("pendingStatus") int pendingStatus,
                                     @Param("targetStatus") int targetStatus,
                                     @Param("errorCode") String errorCode,
                                     @Param("errorMessage") String errorMessage,
                                     @Param("updatedAt") long updatedAt);

    /** 仅把仍待处理的加群动作更新为终态。 */
    int updateAddResultIfPending(@Param("id") Long id,
                                 @Param("pendingStatus") int pendingStatus,
                                 @Param("targetStatus") int targetStatus,
                                 @Param("errorCode") String errorCode,
                                 @Param("errorMessage") String errorMessage,
                                 @Param("updatedAt") long updatedAt);

    /** 启动恢复：跨租户把 RUNNING 执行的待处理联系人和 ADD 明细置为失败。 */
    @InterceptorIgnore(tenantLine = "true")
    int failStalePulling(@Param("row") HistoricalGroupPullMember row,
                         @Param("runningStatus") int runningStatus,
                         @Param("pendingStatus") int pendingStatus,
                         @Param("failedStatus") int failedStatus);

    /** 以成员待发送状态认领唯一命令号。 */
    int markSendSendingIfPending(@Param("id") Long id,
                                 @Param("pendingStatus") int pendingStatus,
                                 @Param("sendingStatus") int sendingStatus,
                                 @Param("commandId") String commandId,
                                 @Param("updatedAt") long updatedAt);

    /** 未匹配到协议身份时，把待发送成员直接冻结为失败。 */
    int markSendFailedIfPending(@Param("row") HistoricalGroupPullMember row,
                                @Param("pendingStatus") int pendingStatus,
                                @Param("failedStatus") int failedStatus);

    /** 命令入队异常或被本地拒绝时，把已认领成员冻结为失败。 */
    int markSendFailedByCommandId(@Param("row") HistoricalGroupPullMember row,
                                  @Param("sendingStatus") int sendingStatus,
                                  @Param("failedStatus") int failedStatus);

    /** 按成员、执行和命令三重身份幂等写入首个协议发送结果。 */
    int updateSendResultIfSending(@Param("row") HistoricalGroupPullMember row,
                                  @Param("sendingStatus") int sendingStatus);

    /** 按命令号幂等消费一次结果事件；已落结果的成员不再覆盖。 */
    int updateSendResultByCommandId(@Param("commandId") String commandId,
                                    @Param("eventId") String eventId,
                                    @Param("sendingStatus") int sendingStatus,
                                    @Param("targetStatus") int targetStatus,
                                    @Param("errorCode") String errorCode,
                                    @Param("errorMessage") String errorMessage,
                                    @Param("updatedAt") long updatedAt);

    /** 启动恢复：跨租户把遗留发送中明细置为失败，且不进行自动重试。 */
    @InterceptorIgnore(tenantLine = "true")
    int failStaleSending(@Param("sendingStatus") int sendingStatus,
                         @Param("failedStatus") int failedStatus,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage,
                         @Param("updatedAt") long updatedAt);
}
