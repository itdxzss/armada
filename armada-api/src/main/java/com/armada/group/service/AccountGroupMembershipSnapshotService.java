package com.armada.group.service;

import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;

/**
 * 将单个账号经过 baseline 过滤后的可见群关系写入本地群表。
 */
public interface AccountGroupMembershipSnapshotService {

    /**
     * 用协议层本次返回的可见群集合替换账号当前活跃群关系。
     *
     * <p>调用方必须先过滤导入前 baseline 旧群。传入空列表表示明确清空账号当前活跃群关系,
     * 用于待拍账号完成 baseline 捕获后,或账号当前没有任何新增可营销群的场景。</p>
     *
     * @param accountId 账号 ID
     * @param groups    已经过 baseline 过滤的可见群
     * @param snapshotComplete 本次快照是否可用于校准缺失关系
     * @param syncAt    协议查询时间(epoch 毫秒)
     * @param eventId   协议层事件 ID,用于跨层日志关联
     * @param source    群列表同步来源
     * @param observedBackend 本次群列表使用的协议后端
     * @return 刷新后的当前群快照；新增群资格由六表当前模型统一判断
     */
    List<AccountGroupMembershipSnapshot> replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId,
            String source,
            ProtocolBackend observedBackend);
}
