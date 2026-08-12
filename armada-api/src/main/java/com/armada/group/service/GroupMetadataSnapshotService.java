package com.armada.group.service;

import com.armada.group.model.dto.GroupMetadataSnapshotRequest;
import com.armada.group.model.vo.GroupExecutionAccount;

/** 读取并持久化最后一次完整群详情快照。 */
public interface GroupMetadataSnapshotService extends GroupMetadataSyncExecutor {

    /**
     * 读取并持久化一次完整群快照。
     *
     * <p>耐久队列的 {@code execute} 与群组列表批量刷新的实时直调都收敛到这里,
     * 保证协议读取、字段级空值保护与成员快照落库只有一套实现。</p>
     *
     * @param request 快照读取请求
     * @param account 固定执行账号
     */
    void refresh(GroupMetadataSnapshotRequest request, GroupExecutionAccount account);
}
