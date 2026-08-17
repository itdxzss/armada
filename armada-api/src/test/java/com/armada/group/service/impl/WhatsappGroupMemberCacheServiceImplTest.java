package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupIdentityMergeFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
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

    @Test
    void identityMergeWritesWhenBothIdentitiesStillLiveOnOneRow() {
        WhatsappGroupIdentityMergeFact fact = mergeFact();
        when(mapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us",
                List.of("15550000001@s.whatsapp.net", "888777666@lid")))
                .thenReturn(List.of(new WhatsappGroupMemberStateVO(
                        "15550000001@s.whatsapp.net", "15550000001", false, false,
                        "member", true, "FULL_SNAPSHOT", 900L)));

        service().applyIdentityMerges(List.of(fact));

        verify(currentPersistence).applyParticipantIdentityMerges(List.of(fact));
    }

    @Test
    void identityMergeSkipsWhenThePersonAlreadySplitAcrossTwoRows() {
        // 两个身份各自命中一行：写入会同时命中 PN 与 LID 两个唯一键，MySQL 直接报重复键。
        when(mapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us",
                List.of("15550000001@s.whatsapp.net", "888777666@lid")))
                .thenReturn(List.of(
                        new WhatsappGroupMemberStateVO(
                                "15550000001@s.whatsapp.net", "15550000001", false, false,
                                "member", true, "FULL_SNAPSHOT", 900L),
                        new WhatsappGroupMemberStateVO(
                                "888777666@lid", null, false, false,
                                "member", true, "ADD_EVENT", 950L)));

        service().applyIdentityMerges(List.of(mergeFact()));

        verify(currentPersistence, never()).applyParticipantIdentityMerges(
                org.mockito.ArgumentMatchers.anyList());
    }

    private static WhatsappGroupIdentityMergeFact mergeFact() {
        return new WhatsappGroupIdentityMergeFact(
                7L, "120363-TEST@G.US", "15550000001@s.whatsapp.net", "888777666@lid",
                "15550000001", 1_000L, "modify-1");
    }

    private WhatsappGroupMemberCacheServiceImpl service() {
        return new WhatsappGroupMemberCacheServiceImpl(mapper, currentPersistence);
    }
}
