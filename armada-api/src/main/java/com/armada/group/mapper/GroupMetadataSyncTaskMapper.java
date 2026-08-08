package com.armada.group.mapper;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群详情耐久同步任务数据访问。 */
@Mapper
public interface GroupMetadataSyncTaskMapper {

    /**
     * 幂等排队每租户每群一行的同步任务；运行中任务仅登记完成后重跑。
     *
     * @param row 待排队任务
     * @param runningStatus RUNNING 稳定码
     * @return 影响行数
     */
    int enqueue(@Param("row") GroupMetadataSyncTask row,
                @Param("runningStatus") int runningStatus);

    /**
     * 按群入口查询当前租户任务。
     *
     * @param groupLinkId 群入口 ID
     * @return 任务；不存在返回 null
     */
    GroupMetadataSyncTask selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);

    /**
     * 把当前租户已过期的运行租约恢复为待执行。
     *
     * @param row 恢复后的状态、时间与错误摘要
     * @param runningStatus RUNNING 稳定码
     * @return 恢复行数
     */
    int recoverExpiredLeases(@Param("row") GroupMetadataSyncTask row,
                             @Param("runningStatus") int runningStatus);

    /** 跨租户恢复所有过期租约。 */
    @InterceptorIgnore(tenantLine = "true")
    int recoverExpiredLeasesAll(@Param("row") GroupMetadataSyncTask row,
                                @Param("runningStatus") int runningStatus);

    /**
     * 跨租户读取到期候选小页；历史成功任务缺少下次执行时间时也纳入一次周期对账。
     *
     * @param statuses 本次允许读取的状态
     * @param periodicStatus 周期对账使用的成功状态码
     * @param now 当前时间(epoch 毫秒)
     * @param limit 最多读取行数
     * @return 按到期时间排序的候选任务
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupMetadataSyncTask> selectDueCandidates(
            @Param("statuses") List<Integer> statuses,
            @Param("periodicStatus") int periodicStatus,
            @Param("now") long now,
            @Param("limit") int limit);

    /**
     * 在租户与账号并发上限内原子领取任务。
     *
     * <p>周期能力上线前的成功任务没有 next_run_at，允许按成功状态领取一次；
     * 完成后会写入正常的下一次执行时间。</p>
     *
     * @param row 本次领取要写入的运行状态
     * @param eligibleStatuses 允许领取的原状态
     * @param runningStatus 运行中状态码
     * @param periodicStatus 周期对账使用的成功状态码
     * @param tenantConcurrency 租户并发上限
     * @param accountConcurrency 账号并发上限
     * @return 成功领取返回 1，否则返回 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int claim(@Param("row") GroupMetadataSyncTask row,
              @Param("eligibleStatuses") List<Integer> eligibleStatuses,
              @Param("runningStatus") int runningStatus,
              @Param("periodicStatus") int periodicStatus,
              @Param("tenantConcurrency") int tenantConcurrency,
              @Param("accountConcurrency") int accountConcurrency);

    /** 仅由 RUNNING 状态完成、延期或失败当前任务。 */
    @InterceptorIgnore(tenantLine = "true")
    int finish(@Param("row") GroupMetadataSyncTask row,
               @Param("runningStatus") int runningStatus);

    /** 尚未领取且无可用账号时进入延期状态。 */
    @InterceptorIgnore(tenantLine = "true")
    int defer(@Param("row") GroupMetadataSyncTask row,
              @Param("eligibleStatuses") List<Integer> eligibleStatuses);

    /** 账号真正上线后恢复其当前在群范围内的延期任务。 */
    int resumeDeferredForAccount(@Param("accountId") Long accountId,
                                 @Param("deferredStatus") int deferredStatus,
                                 @Param("pendingStatus") int pendingStatus,
                                 @Param("triggerSource") int triggerSource,
                                 @Param("now") long now);
}
