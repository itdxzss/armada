package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import java.util.List;
import java.util.Map;

/** WhatsApp 群成员完整缓存服务。 */
public interface WhatsappGroupMemberCacheService {

    /**
     * 查询可供导出复用的群成员快照。
     *
     * <p>优先返回含增量成员状态的营销专用缓存；专用缓存缺失时回退群详情模块保存的
     * 最后一次完整成员快照。两个数据库来源均无数据时不返回对应群。</p>
     *
     * @param tenantId 当前租户 ID
     * @param groupJids WhatsApp 群 JID
     * @return 以规范化群 JID 为键的成员快照
     */
    Map<String, WhatsappGroupMemberCacheSnapshotVO> findByGroupJids(
            Long tenantId,
            List<String> groupJids);

    WhatsappGroupMemberCacheSnapshotVO replaceCompleteSnapshot(
            Long tenantId,
            Long observerAccountId,
            String groupJid,
            GroupMetadataResult metadata,
            long snapshotAt);

    void applyJoins(List<WhatsappGroupJoinFact> facts);

    void applyDepartures(List<WhatsappGroupDepartureFact> facts);
}
