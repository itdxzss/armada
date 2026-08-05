package com.armada.marketing.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import com.armada.marketing.export.service.impl.MarketingTaskWhatsAppMemberProvider;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketingTaskWhatsAppMemberProviderTest {

    @Mock private MarketingTaskExportMapper mapper;
    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private FixedAccountGroupMetadataPort metadataPort;
    @Mock private WhatsappGroupMemberCacheService memberCacheService;
    @Mock private WhatsappGroupDepartedMemberService departedMemberService;
    @Mock private WhatsappGroupMemberJoinFactService joinFactService;

    @BeforeEach
    void returnPersistedSnapshotForLiveMetadata() {
        org.mockito.Mockito.lenient().when(memberCacheService.replaceCompleteSnapshot(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(GroupMetadataResult.class),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    String groupJid = invocation.getArgument(2);
                    GroupMetadataResult metadata = invocation.getArgument(3);
                    long snapshotAt = invocation.getArgument(4);
                    Long observerAccountId = invocation.getArgument(1);
                    List<WhatsappGroupMemberStateVO> members = metadata.participants().stream()
                            .map(participant -> new WhatsappGroupMemberStateVO(
                                    participant.jid(), participant.phone(), participant.admin(),
                                    participant.owner(), participant.role(), true,
                                    "FULL_SNAPSHOT", snapshotAt))
                            .toList();
                    return new WhatsappGroupMemberCacheSnapshotVO(
                            groupJid, metadata.subject(), metadata.announce(), snapshotAt,
                            observerAccountId, members);
                });
    }

    @Test
    void collectUsesWhatsAppMembersAndExcludesRejoinedDeparture() {
        MarketingTaskGroupExportRow group = group();
        group.setJoinedTaskAt(800L);
        group.setObserverAccountId(10L);
        group.setObserverCandidateRank(1);
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");

        when(mapper.selectGroupRowsList(7L, List.of(179L), 1_000L)).thenReturn(List.of(group));
        when(accountLookupService.findActiveProtocolRefs(List.of(10L))).thenReturn(List.of(account));
        when(metadataPort.getMetadata(account, "120363-test@g.us")).thenReturn(new GroupMetadataResult(
                "120363-test@g.us", "WhatsApp真实群", true, null, null, null, null,
                null, false, null, false, true,
                List.of(
                        new GroupParticipantResult("15550000001@s.whatsapp.net", "15550000001", true, false, "admin"),
                        new GroupParticipantResult("551100000002@s.whatsapp.net", "551100000002", false, false, ""))));
        when(departedMemberService.findByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupDepartedMemberVO("120363-test@g.us", "15550000001@s.whatsapp.net",
                        "15550000001", 900L, "LEFT", "HISTORY_SYNC"),
                new WhatsappGroupDepartedMemberVO("120363-test@g.us", "521100000003:7@s.whatsapp.net",
                        "+52 11 0000 0003", 940L, "LEFT", "WGP2_NOTIFICATION"),
                new WhatsappGroupDepartedMemberVO("120363-test@g.us", "521100000003@s.whatsapp.net",
                        null, 950L, "REMOVED", "WGP2_NOTIFICATION")));
        when(joinFactService.findByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupJoinFactVO(
                        "120363-test@g.us", "15550000001@s.whatsapp.net", "15550000001", 850L),
                new WhatsappGroupJoinFactVO(
                        "120363-test@g.us", "551100000002@s.whatsapp.net", "551100000002", 700L),
                new WhatsappGroupJoinFactVO(
                        "120363-test@g.us", "521100000003@s.whatsapp.net", "521100000003", 900L)));

        MarketingTaskWhatsAppMemberProvider provider = new MarketingTaskWhatsAppMemberProvider(
                mapper, accountLookupService, metadataPort, memberCacheService,
                departedMemberService, joinFactService);
        CountryService.PhonePrefixResolver countries = phone -> phone.startsWith("55")
                ? new CountryOptionVO("BR", "BR", "巴西", "Brazil", "+55", "", false)
                : new CountryOptionVO("US", "US", "美国", "United States", "+1", "", false);

        List<MarketingTaskGroupExportRow> groups = new java.util.ArrayList<>();
        List<MarketingTaskGroupMemberExportRow> members = new java.util.ArrayList<>();
        List<MarketingTaskCountryEntryExportRow> countryEntries = new java.util.ArrayList<>();
        provider.streamFull(
                request(countries),
                new MarketingTaskWhatsAppMemberProvider.FullOutput(groups::add, members::add));
        provider.streamCountry(
                request(countries), countryEntries::add);

        assertThat(groups).singleElement().satisfies(row -> {
            assertThat(row.getGroupName()).isEqualTo("WhatsApp真实群");
            assertThat(row.getGroupMemberCount()).isEqualTo(2);
            assertThat(row.getSpeechPermission()).isEqualTo("仅管理员可发言（发送账号可发言）");
            assertThat(row.getJoinedPhoneCount()).isEqualTo(2);
        });
        assertThat(members).hasSize(3);
        assertThat(members).extracting(row -> row.getMemberPhone())
                .containsExactlyInAnyOrder("15550000001", "551100000002", "521100000003");
        assertThat(members).filteredOn(row -> "否".equals(row.getInGroup()))
                .singleElement().satisfies(row -> {
                    assertThat(row.getExitType()).isEqualTo("退出原因未识别");
                    assertThat(row.getJoinedAt()).isEqualTo(900L);
                    assertThat(row.getExitedAt()).isEqualTo(950L);
                });
        assertThat(members).filteredOn(row -> "15550000001".equals(row.getMemberPhone()))
                .singleElement()
                .satisfies(row -> assertThat(row.getJoinedAt()).isEqualTo(850L));
        assertThat(countryEntries).hasSize(3);
        assertThat(countryEntries).extracting(row -> row.getCountryIso2())
                .containsExactlyInAnyOrder("US", "BR", "US");
        assertThat(countryEntries).allSatisfy(row -> assertThat(row.getJoinedAt()).isNotNull());
        assertThat(countryEntries).allSatisfy(
                row -> assertThat(row.getJoinedPhoneCount()).isEqualTo(2));
    }

    @Test
    void collectUsesCompleteCacheWhenObserverIsUnavailable() {
        MarketingTaskGroupExportRow group = group();
        group.setGroupJid("120363-TEST@G.US");
        group.setObserverAccountId(10L);
        group.setObserverCandidateRank(1);
        WhatsappGroupMemberCacheSnapshotVO cache = new WhatsappGroupMemberCacheSnapshotVO(
                "120363-test@g.us", "缓存群名", false, 900L, 10L,
                List.of(new WhatsappGroupMemberStateVO(
                        "551100000002@s.whatsapp.net", "551100000002",
                        false, false, "", true, "FULL_SNAPSHOT", 900L)));

        when(mapper.selectGroupRowsList(7L, List.of(179L), 1_000L)).thenReturn(List.of(group));
        when(memberCacheService.findByGroupJids(7L, List.of("120363-test@g.us")))
                .thenReturn(Map.of("120363-test@g.us", cache));
        when(departedMemberService.findByGroupJids(7L, List.of("120363-test@g.us")))
                .thenReturn(List.of());
        when(joinFactService.findByGroupJids(7L, List.of("120363-test@g.us")))
                .thenReturn(List.of());
        MarketingTaskWhatsAppMemberProvider provider = new MarketingTaskWhatsAppMemberProvider(
                mapper, accountLookupService, metadataPort, memberCacheService,
                departedMemberService, joinFactService);

        List<MarketingTaskGroupExportRow> groups = new java.util.ArrayList<>();
        List<MarketingTaskGroupMemberExportRow> members = new java.util.ArrayList<>();
        provider.streamFull(
                request(phone -> null),
                new MarketingTaskWhatsAppMemberProvider.FullOutput(groups::add, members::add));

        assertThat(groups).singleElement().satisfies(row -> {
            assertThat(row.getGroupName()).isEqualTo("缓存群名");
            assertThat(row.getGroupMemberCount()).isEqualTo(1);
            assertThat(row.getSpeechPermission()).isEqualTo("所有成员可发言");
        });
        assertThat(members).singleElement()
                .satisfies(row -> assertThat(row.getMemberPhone()).isEqualTo("551100000002"));
        org.mockito.Mockito.verifyNoInteractions(metadataPort);
        org.mockito.Mockito.verifyNoInteractions(accountLookupService);
    }

    @Test
    void collectRetriesOneFallbackObserverWhenPrimaryQueryFails() {
        MarketingTaskGroupExportRow group = group();
        group.setObserverAccountId(10L);
        group.setObserverCandidateRank(1);
        MarketingTaskGroupExportRow fallbackGroup = group();
        fallbackGroup.setObserverAccountId(11L);
        fallbackGroup.setObserverCandidateRank(2);
        ProtocolAccountRef primary = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");
        ProtocolAccountRef fallback = new ProtocolAccountRef(
                11L, ProtocolBackend.ANDROID, "android-11", "15550000002");

        when(mapper.selectGroupRowsList(7L, List.of(179L), 1_000L))
                .thenReturn(List.of(group, fallbackGroup));
        when(accountLookupService.findActiveProtocolRefs(List.of(10L, 11L)))
                .thenReturn(List.of(primary, fallback));
        when(departedMemberService.findByGroupJids(7L, List.of("120363-test@g.us")))
                .thenReturn(List.of());
        when(metadataPort.getMetadata(primary, "120363-test@g.us"))
                .thenThrow(new IllegalStateException("primary offline"));
        when(metadataPort.getMetadata(fallback, "120363-test@g.us"))
                .thenReturn(new GroupMetadataResult(
                        "120363-test@g.us", "fallback", false, null, null, null, null,
                        null, false, null, false, true, List.of()));

        MarketingTaskWhatsAppMemberProvider provider = new MarketingTaskWhatsAppMemberProvider(
                mapper, accountLookupService, metadataPort, memberCacheService,
                departedMemberService, joinFactService);

        List<MarketingTaskGroupExportRow> groups = new java.util.ArrayList<>();
        provider.streamFull(
                request(phone -> null),
                new MarketingTaskWhatsAppMemberProvider.FullOutput(groups::add, ignored -> { }));

        assertThat(groups).singleElement()
                .satisfies(row -> assertThat(row.getGroupName()).isEqualTo("fallback"));
        verify(metadataPort).getMetadata(primary, "120363-test@g.us");
        verify(metadataPort).getMetadata(fallback, "120363-test@g.us");
    }

    @Test
    void collectDoesNotTreatMissingPhoneAsTheSameMember() {
        MarketingTaskGroupExportRow group = group();
        group.setObserverAccountId(10L);
        group.setObserverCandidateRank(1);
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");

        when(mapper.selectGroupRowsList(7L, List.of(179L), 1_000L)).thenReturn(List.of(group));
        when(accountLookupService.findActiveProtocolRefs(List.of(10L))).thenReturn(List.of(account));
        when(metadataPort.getMetadata(account, "120363-test@g.us")).thenReturn(new GroupMetadataResult(
                "120363-test@g.us", "群", false, null, null, null, null,
                null, false, null, false, true,
                List.of(new GroupParticipantResult(
                        "current@s.whatsapp.net", null, false, false, ""))));
        when(departedMemberService.findByGroupJids(7L, List.of("120363-test@g.us"))).thenReturn(List.of(
                new WhatsappGroupDepartedMemberVO(
                        "120363-test@g.us", "current@s.whatsapp.net", null, 800L,
                        "LEFT", "HISTORY_SYNC"),
                new WhatsappGroupDepartedMemberVO(
                        "120363-test@g.us", "departed@s.whatsapp.net", null, 900L,
                        "REMOVED", "HISTORY_SYNC"),
                new WhatsappGroupDepartedMemberVO(
                        "120363-test@g.us", "left@s.whatsapp.net", null, 910L,
                        "LEFT", "WGP2_NOTIFICATION"),
                new WhatsappGroupDepartedMemberVO(
                        "120363-test@g.us", "unknown@s.whatsapp.net", null, 920L,
                        "UNKNOWN", "WGP2_NOTIFICATION")));

        MarketingTaskWhatsAppMemberProvider provider = new MarketingTaskWhatsAppMemberProvider(
                mapper, accountLookupService, metadataPort, memberCacheService,
                departedMemberService, joinFactService);

        List<MarketingTaskGroupMemberExportRow> members = new java.util.ArrayList<>();
        provider.streamFull(
                request(phone -> null),
                new MarketingTaskWhatsAppMemberProvider.FullOutput(ignored -> { }, members::add));

        assertThat(members).extracting(row -> row.getMemberPhone())
                .containsExactlyInAnyOrder(
                        "current@s.whatsapp.net", "departed@s.whatsapp.net",
                        "left@s.whatsapp.net", "unknown@s.whatsapp.net");
        assertThat(members).filteredOn(row -> "departed@s.whatsapp.net".equals(row.getMemberPhone()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getExitType()).isEqualTo("被移出群");
                    assertThat(row.getExitedAt()).isEqualTo(900L);
                });
        assertThat(members).filteredOn(row -> "left@s.whatsapp.net".equals(row.getMemberPhone()))
                .singleElement()
                .satisfies(row -> assertThat(row.getExitType()).isEqualTo("主动退群"));
        assertThat(members).filteredOn(row -> "unknown@s.whatsapp.net".equals(row.getMemberPhone()))
                .singleElement()
                .satisfies(row -> assertThat(row.getExitType()).isEqualTo("退出原因未识别"));
    }

    @Test
    void streamCountryDoesNotTreatLidAsPhoneWithoutTrustedMapping() {
        MarketingTaskGroupExportRow group = group();
        group.setObserverAccountId(10L);
        group.setObserverCandidateRank(1);
        ProtocolAccountRef account = new ProtocolAccountRef(
                10L, ProtocolBackend.ANDROID, "android-10", "15550000001");

        when(mapper.selectGroupRowsList(7L, List.of(179L), 1_000L)).thenReturn(List.of(group));
        when(accountLookupService.findActiveProtocolRefs(List.of(10L))).thenReturn(List.of(account));
        when(metadataPort.getMetadata(account, "120363-test@g.us")).thenReturn(new GroupMetadataResult(
                "120363-test@g.us", "群", false, null, null, null, null,
                null, false, null, false, true,
                List.of(new GroupParticipantResult(
                        "123456789012345@lid", null, false, false, ""))));
        when(departedMemberService.findByGroupJids(7L, List.of("120363-test@g.us")))
                .thenReturn(List.of());
        MarketingTaskWhatsAppMemberProvider provider = new MarketingTaskWhatsAppMemberProvider(
                mapper, accountLookupService, metadataPort, memberCacheService,
                departedMemberService, joinFactService);
        List<MarketingTaskCountryEntryExportRow> countryEntries = new java.util.ArrayList<>();

        provider.streamCountry(
                request(phone -> new CountryOptionVO(
                        "US", "US", "美国", "United States", "+1", "", false)),
                countryEntries::add);

        assertThat(countryEntries).isEmpty();
    }

    private static MarketingTaskGroupExportRow group() {
        MarketingTaskGroupExportRow row = new MarketingTaskGroupExportRow();
        row.setTaskId(179L);
        row.setTaskName("任务179");
        row.setGroupJid("120363-test@g.us");
        row.setGroupName("旧群名");
        row.setGroupLink("https://chat.whatsapp.com/test");
        row.setGroupStatus("正常");
        row.setSenderPhone("15559999999");
        row.setSuccessCount(14);
        return row;
    }

    private static MarketingTaskWhatsAppMemberProvider.ExportRequest request(
            CountryService.PhonePrefixResolver countries) {
        return new MarketingTaskWhatsAppMemberProvider.ExportRequest(
                7L, List.of(179L), 1_000L, countries, () -> { });
    }
}
