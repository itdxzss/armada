package com.armada.marketing.export.mapper;

import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import com.armada.marketing.model.entity.MarketingTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;

/** 普通营销任务异步导出作业与导出事实查询。 */
@Mapper
public interface MarketingTaskExportMapper {

    /**
     * @param taskIds 营销任务 ID 集合
     * @return 当前租户可见且未删除的任务
     */
    List<MarketingTask> selectTasksByIds(@Param("taskIds") List<Long> taskIds);

    /**
     * @param job 待持久化的导出作业
     * @return 插入行数
     */
    int insertJob(MarketingTaskExportJob job);

    /**
     * @param id 作业 ID
     * @param createdBy 创建用户 ID
     * @return 当前租户和用户可见的作业
     */
    MarketingTaskExportJob selectJobByIdForUser(@Param("id") Long id,
                                                @Param("createdBy") Long createdBy);

    /**
     * @param tenantId 租户 ID
     * @param createdBy 创建用户 ID
     * @param requestHash 规范化请求哈希
     * @return 相同范围内仍在排队或处理的活动作业
     */
    MarketingTaskExportJob selectActiveJob(@Param("tenantId") Long tenantId,
                                           @Param("createdBy") Long createdBy,
                                           @Param("requestHash") String requestHash);

    /**
     * @param now 当前时间
     * @param limit 返回上限
     * @return 可领取或可重试的跨租户作业
     */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTaskExportJob> selectProcessableJobs(@Param("now") long now, @Param("limit") int limit);

    /**
     * @param now 当前时间
     * @param errorMessage 稳定失败原因
     * @return 标记失败的作业数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markExhaustedJobs(@Param("now") long now, @Param("errorMessage") String errorMessage);

    /**
     * @param now 当前时间
     * @param limit 返回上限
     * @return 已过期且仍记录文件键的成功作业
     */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTaskExportJob> selectExpiredFiles(@Param("now") long now, @Param("limit") int limit);

    /**
     * @param tenantId 租户 ID
     * @param id 作业 ID
     * @param storageKey 待清理文件的相对键
     * @param now 清理时间
     * @return 清除存储元数据的行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int clearExpiredStorage(@Param("tenantId") Long tenantId,
                            @Param("id") Long id,
                            @Param("storageKey") String storageKey,
                            @Param("now") long now);

    /**
     * @param tenantId 租户 ID
     * @param id 作业 ID
     * @param now 领取时间
     * @param leaseUntil 租约截止时间
     * @param claimToken 本次 Worker 唯一领取令牌
     * @return 成功领取的行数，零表示已被其他 Worker 领取
     */
    @InterceptorIgnore(tenantLine = "true")
    int claimJob(@Param("tenantId") Long tenantId,
                 @Param("id") Long id,
                 @Param("now") long now,
                 @Param("leaseUntil") long leaseUntil,
                 @Param("claimToken") String claimToken);

    /**
     * 仅允许持有当前领取令牌的 Worker 延长处理租约。
     *
     * @param tenantId 作业所属租户
     * @param id 导出作业 ID
     * @param claimToken 当前 Worker 的领取令牌
     * @param now 本次续租时间
     * @param leaseUntil 新租约截止时间
     * @return 更新行数；零表示作业已被其他 Worker 接管或不再处理中
     */
    @InterceptorIgnore(tenantLine = "true")
    int renewJobLease(@Param("tenantId") Long tenantId,
                      @Param("id") Long id,
                      @Param("claimToken") String claimToken,
                      @Param("now") long now,
                      @Param("leaseUntil") long leaseUntil);

    /**
     * @param job 已填充领取令牌和文件结果的作业
     * @return 成功完成的行数，零表示令牌已失效
     */
    @InterceptorIgnore(tenantLine = "true")
    int markJobSuccess(MarketingTaskExportJob job);

    /**
     * @param tenantId 租户 ID
     * @param id 作业 ID
     * @param claimToken 当前 Worker 领取令牌
     * @param errorMessage 稳定失败原因
     * @param finishedAt 失败完成时间
     * @return 成功标记失败的行数，零表示令牌已失效
     */
    @InterceptorIgnore(tenantLine = "true")
    int markJobFailed(@Param("tenantId") Long tenantId,
                      @Param("id") Long id,
                      @Param("claimToken") String claimToken,
                      @Param("errorMessage") String errorMessage,
                      @Param("finishedAt") long finishedAt);

    /**
     * 逐行读取按国家导出的成功进群事实；MIN_VALUE 是 Connector/J 未启用游标时的真流式语义。
     *
     * @param tenantId 导出作业所属租户 ID
     * @param taskIds 普通营销任务 ID 集合
     * @param snapshotAt 数据快照截止时间
     * @param resultHandler 逐行结果处理器
     */
    @InterceptorIgnore(tenantLine = "true")
    @Options(fetchSize = Integer.MIN_VALUE, resultSetType = ResultSetType.FORWARD_ONLY)
    void selectCountryEntryRows(
            @Param("tenantId") Long tenantId,
            @Param("taskIds") List<Long> taskIds,
            @Param("snapshotAt") long snapshotAt,
            ResultHandler<MarketingTaskCountryEntryExportRow> resultHandler);

    /**
     * 逐行读取全量导出的群组明细，保持与国家明细相同的 Connector/J 真流式语义。
     *
     * @param tenantId 导出作业所属租户 ID
     * @param taskIds 普通营销任务 ID 集合
     * @param snapshotAt 数据快照截止时间
     * @param resultHandler 逐行结果处理器
     */
    @InterceptorIgnore(tenantLine = "true")
    @Options(fetchSize = Integer.MIN_VALUE, resultSetType = ResultSetType.FORWARD_ONLY)
    void selectGroupRows(
            @Param("tenantId") Long tenantId,
            @Param("taskIds") List<Long> taskIds,
            @Param("snapshotAt") long snapshotAt,
            ResultHandler<MarketingTaskGroupExportRow> resultHandler);

    /** 读取全部任务群，供协议实时成员提供器使用。 */
    @InterceptorIgnore(tenantLine = "true")
    List<MarketingTaskGroupExportRow> selectGroupRowsList(
            @Param("tenantId") Long tenantId,
            @Param("taskIds") List<Long> taskIds,
            @Param("snapshotAt") long snapshotAt);

    /** 逐行读取全量导出的受控群成员明细。 */
    @InterceptorIgnore(tenantLine = "true")
    @Options(fetchSize = Integer.MIN_VALUE, resultSetType = ResultSetType.FORWARD_ONLY)
    void selectGroupMemberRows(
            @Param("tenantId") Long tenantId,
            @Param("taskIds") List<Long> taskIds,
            @Param("snapshotAt") long snapshotAt,
            ResultHandler<MarketingTaskGroupMemberExportRow> resultHandler);
}
