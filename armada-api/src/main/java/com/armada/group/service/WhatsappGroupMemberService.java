package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupParticipant;
import com.armada.group.model.dto.WhatsappGroupParticipantsChangedEvent;
import java.util.List;

/** WhatsApp 群全成员快照与精确进退群事实服务。 */
public interface WhatsappGroupMemberService {

    /** 观察 WhatsApp participant 快照；仅在完整性标记和人数核对都通过时清理缺失成员。 */
    void replaceCurrentMembers(
            Long observerAccountId,
            Long groupLinkId,
            String groupJid,
            List<WhatsappGroupParticipant> participants,
            Integer declaredMemberCount,
            boolean participantsComplete,
            Boolean announceOnly,
            Boolean observerAdmin,
            long snapshotAt,
            String sourceEventId);

    /** 应用普通成员 add/remove/leave 精确事件。 */
    void applyParticipantsChanged(WhatsappGroupParticipantsChangedEvent event);
}
