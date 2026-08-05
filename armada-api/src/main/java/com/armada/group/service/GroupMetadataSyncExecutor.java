package com.armada.group.service;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.vo.GroupExecutionAccount;

/** 单群详情同步任务的远程执行边界。 */
public interface GroupMetadataSyncExecutor {

    /**
     * 在已领取租约下读取并持久化单群详情。
     *
     * @param task 已领取任务
     * @param account 固定执行账号
     */
    void execute(GroupMetadataSyncTask task, GroupExecutionAccount account);
}
