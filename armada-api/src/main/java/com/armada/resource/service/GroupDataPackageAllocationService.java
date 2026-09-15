package com.armada.resource.service;

import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import java.util.List;

/** 拉群任务通过本服务领取资源，不穿透资源 Mapper 或实体。 */
public interface GroupDataPackageAllocationService {
    /** 读取未用号码快照；不占用资源，提交时重新校验。 */
    Snapshot snapshot(long packageId, int limit);
    /** 必须整批原子领取；任一号码冲突时回滚。 */
    List<Phone> claim(ClaimRequest request);
    /** 只释放调用方已确认没有外部执行的占用；待确认号码不释放。 */
    void release(List<AllocationRef> allocations);
    /** 同步最新有效执行结果；重复与旧分配结果不改计数。 */
    void settle(List<Settlement> settlements);

    /** 可审查的固定号码集合。 */
    record Snapshot(long packageId, String name, int generation, List<Phone> phones) { }
    /** 号码源快照；claim返回实际分配版本。 */
    record Phone(long id, String phone, boolean adminRequired, int memberSeq,
                 int sourceLineNo, String countryIso2, long allocationVersion) { }
    /** 同一个任务逻辑执行单元领取的号码。 */
    record ClaimRequest(long packageId, int generation, long taskId,
                        int executionSeq, List<Long> phoneIds) { }
    /** 原任务/执行单元/分配版本，避免旧回调更新新领取。 */
    record AllocationRef(long phoneId, long allocationVersion, long taskId, int executionSeq) { }
    /** 仅由任务域确认的最新有效执行结果。 */
    record Settlement(AllocationRef allocation, GroupDataPackagePhoneStatus status) { }
}
