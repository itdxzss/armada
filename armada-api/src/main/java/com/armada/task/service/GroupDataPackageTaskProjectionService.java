package com.armada.task.service;

import java.util.List;

/** 资源菜单从标准拉群实际执行事实同步结果，跨域只通过服务调用。 */
public interface GroupDataPackageTaskProjectionService {
    /** 仅同步指定数据包的已领取来源；不发出任何外部协议动作。 */
    void synchronize(List<Long> packageIds);
    /** 高频逐号码回调仅结算给定执行行的指定成员，旧群回调不覆盖新群。 */
    void synchronizeMaterialMembers(long executionId, List<Long> memberIds);
    /** 单个执行的结束、换群和自然完成只锁这个执行，避免扩锁同包其他任务。 */
    void synchronizeExecution(long executionId);
    /** 执行结果或生命周期变更后同步该任务使用的包。 */
    void synchronizeTask(long taskId);
    /** 从真实任务历史查近期隐私拒绝号码，不把任意 403 当成隐私拒绝。 */
    java.util.Set<String> privacyRejectedPhones(List<String> phones, long cutoff);
    /** 覆盖、删除和人工回收前拒绝仍被运行、暂停或待启动任务引用的数据包。 */
    void assertNotActivelyUsed(long packageId);
}
