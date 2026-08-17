package com.armada.group.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.group.mapper.WhatsappGroupMemberJoinFactMapper;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 成员进群事实服务只写当前模型的边界测试。 */
@ExtendWith(MockitoExtension.class)
class WhatsappGroupMemberJoinFactServiceImplTest {

    @Mock
    private WhatsappGroupMemberJoinFactMapper mapper;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    @Test
    void saveLatestWritesOnlyCurrentParticipantFacts() {
        WhatsappGroupJoinFact second = fact("group-b@g.us", "2@s.whatsapp.net");
        WhatsappGroupJoinFact first = fact("group-a@g.us", "1@s.whatsapp.net");
        List<WhatsappGroupJoinFact> facts = List.of(second, first);
        WhatsappGroupMemberJoinFactServiceImpl service =
                new WhatsappGroupMemberJoinFactServiceImpl(mapper, currentPersistence);

        service.saveLatest(facts);

        verify(currentPersistence).applyParticipantJoins(facts);
        verifyNoInteractions(mapper);
    }

    private static WhatsappGroupJoinFact fact(String groupJid, String participantJid) {
        return new WhatsappGroupJoinFact(
                7L, groupJid, participantJid, "15550000001",
                100L, 100L, "event-1", 10L);
    }
}
