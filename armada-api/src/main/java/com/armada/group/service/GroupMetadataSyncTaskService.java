package com.armada.group.service;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import java.util.List;
import java.util.Map;

/** 群详情耐久同步任务状态机。 */
public interface GroupMetadataSyncTaskService {

    /** 幂等排队单群同步任务。 */
    void enqueue(Long groupLinkId, GroupMetadataSyncTrigger trigger, long triggeredAt);

    /**
     * 按群入口主键升序一次排队本轮新固化分类的详情任务。
     *
     * @param triggersByGroupLinkId 句柄 ID 到分类触发来源的映射
     * @param triggeredAt 分类触发时间(epoch毫秒)
     */
    void enqueueClassifications(
            Map<Long, GroupMetadataSyncTrigger> triggersByGroupLinkId,
            long triggeredAt);

    /**
     * phase2 绑定提交前补建缺失分类任务或恢复延期分类任务；其它既有状态保持不变。
     */
    void reconcileClassifications(
            Map<Long, GroupMetadataSyncTrigger> triggersByGroupLinkId,
            long triggeredAt);

    /** 幂等排队单群邀请码读取；metadata 已由当前业务事实确认，不再重复请求。 */
    void enqueueInviteCode(Long groupLinkId, GroupMetadataSyncTrigger trigger, long triggeredAt);

    /** 恢复当前账号在群范围内的延期任务。 */
    /**
     * 账号上线后只恢复已完成 metadata、尚缺邀请码的延期任务。
     *
     * @param accountId 上线账号 ID
     * @param now 当前时间(epoch 毫秒)
     */
    void resumeDeferredInviteCodeForAccount(Long accountId, long now);

    /** 跨租户恢复过期运行租约。 */
    int recoverExpiredLeases(long now);

    /** 跨租户查询到期候选。 */
    List<GroupMetadataSyncTask> findDue(long now, int limit);

    /** 在数据库并发约束下尝试领取任务。 */
    boolean claim(GroupMetadataSyncTask task,
                  GroupExecutionAccount account,
                  long now,
                  long leaseUntil,
                  GroupMetadataSyncLimits limits);

    /** 无执行账号时延期，不消耗尝试次数。 */
    void defer(GroupMetadataSyncTask task, long now);

    /** 标记成功并安排周期对账；运行中收到新触发时由 SQL 自动优先回到待执行。 */
    void succeed(GroupMetadataSyncTask task, long now);

    /** 按 1/5/30 分钟退避记录失败，第四次进入失败终态。 */
    void fail(GroupMetadataSyncTask task, String errorCode, String errorMessage, long now);
}
