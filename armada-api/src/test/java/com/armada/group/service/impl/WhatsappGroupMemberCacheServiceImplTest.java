package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群成员缓存读取和当前模型写入边界测试。 */
@ExtendWith(MockitoExtension.class)
class WhatsappGroupMemberCacheServiceImplTest {

    @Mock private WhatsappGroupMemberCacheMapper mapper;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    @Test
    void findByGroupJidsReturnsCurrentModelRows() {
        when(mapper.selectByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupMemberCacheRow(
                        "120363-test@g.us", "新模型群", true, 1_000L, null,
                        "15550000001@s.whatsapp.net", "15550000001",
                        true, false, "admin", true, "FULL_SNAPSHOT", 1_000L)));
        WhatsappGroupMemberCacheServiceImpl service = service();

        var result = service.findByGroupJids(7L, List.of("120363-TEST@G.US"));

        assertThat(result).containsOnlyKeys("120363-test@g.us");
        assertThat(result.get("120363-test@g.us").subject()).isEqualTo("新模型群");
        assertThat(result.get("120363-test@g.us").members()).singleElement()
                .satisfies(member -> assertThat(member.stateSource()).isEqualTo("FULL_SNAPSHOT"));
    }

    @Test
    void replaceCompleteSnapshotWritesCurrentMetadataAndParticipants() {
        when(mapper.selectByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupMemberCacheRow(
                        "120363-test@g.us", "真实群", true, 1_000L, null,
                        "15550000001@s.whatsapp.net", "15550000001",
                        true, false, "admin", true, "FULL_SNAPSHOT", 1_000L)));
        GroupMetadataResult metadata = new GroupMetadataResult(
                "120363-test@g.us", "真实群", null, null, null,
                true, true, null, null, null,
                null, null, false, null, false, true,
                List.of(new GroupParticipantResult(
"15550000001@s.whatsapp.net", null, "15550000001",
                        true, false, "admin")));

        var result = service().replaceCompleteSnapshot(
                7L, 10L, "120363-TEST@G.US", metadata, 1_000L);

        ArgumentCaptor<GroupLinkPreview> profile = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(currentPersistence).replaceCompleteGroupMetadataSnapshot(
                profile.capture(), eq(metadata.participants()), eq(1_000L), anyString());
        assertThat(profile.getValue().getGroupJid()).isEqualTo("120363-test@g.us");
        assertThat(profile.getValue().getWaSubject()).isEqualTo("真实群");
        assertThat(result.members()).hasSize(1);
    }

    @Test
    void incrementalEventsWriteOnlyCurrentParticipantFacts() {
        List<WhatsappGroupJoinFact> joins = List.of(new WhatsappGroupJoinFact(
                7L, "120363-test@g.us", "15550000001@s.whatsapp.net", "15550000001",
                900L, 900L, "add-1", 10L));
        List<WhatsappGroupDepartureFact> departures = List.of(new WhatsappGroupDepartureFact(
                7L, "120363-test@g.us", "15550000002@s.whatsapp.net", "15550000002",
                950L, "REMOVED", 950L, "remove-1", "WGP2_NOTIFICATION"));

        WhatsappGroupMemberCacheServiceImpl service = service();
        service.applyJoins(joins);
        service.applyDepartures(departures);

        verify(currentPersistence).applyParticipantJoins(joins);
        verify(currentPersistence).applyParticipantDepartures(departures);
    }

    private WhatsappGroupMemberCacheServiceImpl service() {
        return new WhatsappGroupMemberCacheServiceImpl(mapper, currentPersistence);
    }
}
