package com.armada.group.service;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import java.util.List;

/** 群详情耐久同步任务状态机。 */
public interface GroupMetadataSyncTaskService {

    /** 幂等排队单群同步任务。 */
    void enqueue(Long groupLinkId, GroupMetadataSyncTrigger trigger, long triggeredAt);

    /** 恢复当前账号在群范围内的延期任务。 */
    void resumeDeferredForAccount(Long accountId, long now);

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

    /** 标记成功；运行中收到新触发时由 SQL 自动回到待执行。 */
    void succeed(GroupMetadataSyncTask task, long now);

    /** 按 1/5/30 分钟退避记录失败，第四次进入失败终态。 */
    void fail(GroupMetadataSyncTask task, String errorCode, String errorMessage, long now);
}
