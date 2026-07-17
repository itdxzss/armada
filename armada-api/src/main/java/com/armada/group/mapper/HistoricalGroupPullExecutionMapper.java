package com.armada.group.mapper;

import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 历史群拉人独立执行数据访问。 */
@Mapper
public interface HistoricalGroupPullExecutionMapper {

    /** 插入一次性执行；租户与幂等键共同拒绝重复请求。 */
    int insert(HistoricalGroupPullExecution row);

    /** 按租户与主键查询执行快照。 */
    HistoricalGroupPullExecution selectByTenantAndId(@Param("tenantId") Long tenantId,
                                                       @Param("id") Long id);

    /** 锁定单个执行，串行化同一执行下并发到达的营销结果聚合。 */
    HistoricalGroupPullExecution selectByTenantAndIdForUpdate(@Param("tenantId") Long tenantId,
                                                               @Param("id") Long id);

    /** 按当前租户创建幂等键查询原执行。 */
    HistoricalGroupPullExecution selectByTenantAndIdempotencyKey(
            @Param("tenantId") Long tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 唯一键并发冲突后以当前读重新取得已提交的原执行。 */
    HistoricalGroupPullExecution selectByTenantAndIdempotencyKeyForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 按固定操作账号和目标群查询最近创建的执行。 */
    HistoricalGroupPullExecution selectLatestByTenantAccountAndGroup(
            @Param("tenantId") Long tenantId,
            @Param("operationAccountId") Long operationAccountId,
            @Param("groupJid") String groupJid);

    /** 以期望状态为前置条件原子认领执行。 */
    int claimStatus(@Param("id") Long id,
                    @Param("expectedStatus") int expectedStatus,
                    @Param("targetStatus") int targetStatus,
                    @Param("startedAt") long startedAt);

    /** 原子认领一次营销发送，并固化本次唯一模板。 */
    int claimMarketingIfNotStarted(@Param("id") Long id,
                                   @Param("notStartedStatus") int notStartedStatus,
                                   @Param("sendingStatus") int sendingStatus,
                                   @Param("marketingTemplateId") Long marketingTemplateId,
                                   @Param("updatedAt") long updatedAt);

    /** 没有异步发送成员时，从发送中状态直接收敛营销终态。 */
    int finishMarketingIfSending(@Param("row") HistoricalGroupPullExecution row,
                                 @Param("sendingStatus") int sendingStatus);

    /** 仅在执行仍为运行态时固化本次唯一拉手账号。 */
    int assignPullerIfRunning(@Param("id") Long id,
                              @Param("pullerAccountId") Long pullerAccountId,
                              @Param("runningStatus") int runningStatus,
                              @Param("updatedAt") long updatedAt);

    /** 仅在拉人仍处于运行态时写入终态、错误快照和完成时间。 */
    int finishIfRunning(@Param("row") HistoricalGroupPullExecution row,
                        @Param("runningStatus") int runningStatus);

    /** 从成员终态重新汇总成功、失败与发送计数。 */
    int refreshTerminalStats(@Param("id") Long id,
                             @Param("addSuccessStatus") int addSuccessStatus,
                             @Param("sendSuccessStatus") int sendSuccessStatus,
                             @Param("sendFailedStatus") int sendFailedStatus,
                             @Param("updatedAt") long updatedAt);

    /**
     * 启动恢复：跨租户把遗留 RUNNING/SENDING 执行落为失败，避免一次性执行被再次消费。
     */
    @InterceptorIgnore(tenantLine = "true")
    int failStaleInProgress(@Param("runningPullStatus") int runningPullStatus,
                            @Param("failedPullStatus") int failedPullStatus,
                            @Param("sendingMarketingStatus") int sendingMarketingStatus,
                            @Param("failedMarketingStatus") int failedMarketingStatus,
                            @Param("failureStage") String failureStage,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("finishedAt") long finishedAt);
}
