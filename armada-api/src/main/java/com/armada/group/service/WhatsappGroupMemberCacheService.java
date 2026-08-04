package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import java.util.List;
import java.util.Map;

/** WhatsApp 群成员完整缓存服务。 */
public interface WhatsappGroupMemberCacheService {

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
