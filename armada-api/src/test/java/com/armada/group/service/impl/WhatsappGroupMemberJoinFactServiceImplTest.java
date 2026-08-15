package com.armada.group.service.impl;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import com.armada.group.mapper.WhatsappGroupMemberJoinFactMapper;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 成员进群当前事实服务的旧表与新表写入边界测试。 */
@ExtendWith(MockitoExtension.class)
class WhatsappGroupMemberJoinFactServiceImplTest {

    @Mock
    private WhatsappGroupMemberJoinFactMapper mapper;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    @Test
    void saveLatestWritesLegacyFactsBeforeNewParticipantFacts() {
        WhatsappGroupJoinFact second = fact("group-b@g.us", "2@s.whatsapp.net");
        WhatsappGroupJoinFact first = fact("group-a@g.us", "1@s.whatsapp.net");
        List<WhatsappGroupJoinFact> facts = List.of(second, first);
        WhatsappGroupMemberJoinFactServiceImpl service =
                new WhatsappGroupMemberJoinFactServiceImpl(mapper, currentPersistence);

        service.saveLatest(facts);

        InOrder order = inOrder(mapper, currentPersistence);
        order.verify(mapper).upsertLatest(eq(first), anyLong());
        order.verify(mapper).upsertLatest(eq(second), anyLong());
        order.verify(currentPersistence).applyParticipantJoins(facts);
    }

    private static WhatsappGroupJoinFact fact(String groupJid, String participantJid) {
        return new WhatsappGroupJoinFact(
                7L, groupJid, participantJid, "15550000001",
                100L, 100L, "event-1", 10L);
    }
}
