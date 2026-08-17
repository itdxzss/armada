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

    /**
     * 读取该账号当前仍在群范围内的延期任务主键，按主键升序返回。
     *
     * <p>普通一致性读不加锁，把跨 group_link、账号群关系和成员在群态的存在性判断留在读阶段，
     * 使后续写只需锁本次已确定的少量主键。</p>
     *
     * @param accountId 上线账号
     * @param deferredStatus DEFERRED 稳定码
     * @return 升序任务主键；无候选返回空列表
     */
    List<Long> selectDeferredTaskIdsForAccount(@Param("accountId") Long accountId,
                                               @Param("deferredStatus") int deferredStatus);

    /**
     * 按已确定的主键集合恢复延期任务。
     *
     * <p>单表按主键更新，锁范围只覆盖入参主键；调用方传入升序主键以固定锁序，
     * 避免多个账号同时上线时互相持锁等待。</p>
     *
     * @param ids 升序任务主键，不能为空
     * @param deferredStatus DEFERRED 稳定码
     * @param pendingStatus 恢复后的 PENDING 稳定码
     * @param triggerSource 恢复触发来源
     * @param now 当前时间(epoch 毫秒)
     * @return 恢复行数
     */
    int resumeDeferredByIds(@Param("ids") List<Long> ids,
                            @Param("deferredStatus") int deferredStatus,
                            @Param("pendingStatus") int pendingStatus,
                            @Param("triggerSource") int triggerSource,
                            @Param("now") long now);
}
