package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedEvent;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 锁定单群资料上报事件的接线：资料走字段级 reducer，成员走完整快照落库。
 *
 * <p>最关键的一条是成员部分只在协议声明列表完整时才执行——既有的完整快照落库会把库里有而列表里
 * 没有的成员判为已退群，误判会直接影响营销选号与拉群选管理员。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupProfileReportedSinkAdapterTest {

    @Mock
    private GroupMetadataPatchService patchService;

    @Mock
    private AccountGroupCurrentSnapshotPersistenceImpl snapshotPersistence;

    @Mock
    private GroupMetadataSyncTaskMapper taskMapper;

    @Mock
    private GroupBatchTaskItemMapper batchItemMapper;

    @Mock
    private GroupLinkRegistryService groupLinkRegistryService;

    @Mock
    private GroupCreatorCompatibilityWriter creatorWriter;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private GroupInviteLinkService inviteLinkService;

    /** 同线程执行,让异步取邀请码在单测里可断言。 */
    private final java.util.concurrent.Executor inviteFetchExecutor = Runnable::run;

    private GroupProfileReportedSinkAdapter adapter;

    @org.junit.jupiter.api.BeforeEach
    void buildAdapter() {
        adapter = new GroupProfileReportedSinkAdapter(
                patchService, snapshotPersistence, taskMapper, batchItemMapper,
                groupLinkRegistryService, creatorWriter, accountMapper,
                inviteLinkService, inviteFetchExecutor);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * 建档后立刻主动取一次邀请码,不依赖定时任务轮询。
     *
     * <p>WhatsApp 只在邀请码"发生变更"时才推 group.invite_link_changed(例如有人重置群链接);
     * 建群与进群都不推,因为对它而言邀请码一直存在、没变过。因此必须主动查一次,
     * 否则群组列表的邀请链接一列对新群永远为空。</p>
     *
     * <p>入队 + 定时任务那条路虽然也能取,但要排队等轮询;实测积压时数小时不动。
     * 这里改为建档当场提交异步任务,进群几秒内即可落库。</p>
     */
    @Test
    void profileReportedTriggersActiveInviteCodeFetch() {
        org.mockito.Mockito.when(groupLinkRegistryService.registerAccountObservedGroup(
                anyString(), any(), any(), anyLong())).thenReturn(77L);

        adapter.handleProfileReported(event(true, List.of()));

        verify(inviteLinkService).refreshCurrentInviteCode(eq(77L), eq("120363-abc@g.us"), eq(null));
    }

    /**
     * 主动取邀请码失败不能连累资料落库。
     *
     * <p>取邀请码要向 WhatsApp 发一次请求,失败是常态(号掉线、非管理员、群已封)。
     * 邀请码是补充事实,不该让整条资料事件重投,把群名与成员一起卡住。</p>
     */
    @Test
    void activeInviteFetchFailureDoesNotDropTheProfileFacts() {
        org.mockito.Mockito.when(groupLinkRegistryService.registerAccountObservedGroup(
                anyString(), any(), any(), anyLong())).thenReturn(77L);
        org.mockito.Mockito.doThrow(new RuntimeException("protocol down"))
                .when(inviteLinkService)
                .refreshCurrentInviteCode(anyLong(), anyString(), any());

        adapter.handleProfileReported(event(true, List.of()));

        verify(patchService).applyPatch(any(GroupMetadataPatch.class));
    }

    /**
     * 群入口没登记成时不主动取。
     *
     * <p>groupLinkId 为空时选号与落库都无处落脚,取回来也没地方写。</p>
     */
    @Test
    void missingGroupLinkSkipsActiveInviteFetch() {
        org.mockito.Mockito.when(groupLinkRegistryService.registerAccountObservedGroup(
                anyString(), any(), any(), anyLong())).thenReturn(null);

        adapter.handleProfileReported(event(true, List.of()));

        verify(inviteLinkService, never()).refreshCurrentInviteCode(any(), anyString(), any());
    }

    @Test
    void completeMembersAreWrittenAsFullSnapshot() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "123456789012345@lid", "123456789012345@lid", "919000000001",
                        true, false, "admin"),
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());
        List<GroupParticipantResult> participants = captor.getValue();
        assertThat(participants).hasSize(2);
        assertThat(participants.get(0).phone()).isEqualTo("919000000001");
        assertThat(participants.get(0).pnJid())
                .as("号码要还原成 PN JID 供落库层归位到 pn_jid 列")
                .isEqualTo("919000000001@s.whatsapp.net");
        assertThat(participants.get(0).admin()).isTrue();
    }

    @Test
    void completeMemberWithPnPhoneDoesNotAppendSuffixTwice() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "123456789012345@lid", "123456789012345@lid",
                        "919000000001@s.whatsapp.net", true, false, "admin"))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());

        assertThat(captor.getValue()).singleElement().satisfies(participant -> {
            assertThat(participant.pnJid()).isEqualTo("919000000001@s.whatsapp.net");
            assertThat(participant.pnJid()).doesNotContain("@s.whatsapp.net@s.whatsapp.net");
        });
    }

    @Test
    void completeMemberWithNonNumericPhoneDoesNotAppendPnSuffix() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "123456789012345@lid", "123456789012345@lid",
                        "123456789012345@lid", true, false, "admin"))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());

        assertThat(captor.getValue()).singleElement().satisfies(participant ->
                assertThat(participant.pnJid()).isEqualTo("123456789012345@lid"));
    }

    @Test
    void completeEmptyMembersStillClearsDepartedMembers() {
        ProtocolGroupProfileReportedEvent event = event(true, List.of());

        adapter.handleProfileReported(event);

        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                eq(event.groupJid()), eq(List.of()), eq(event.occurredAt()), eq(event.eventId()));
    }

    @Test
    void incompleteMembersAreSkippedToAvoidFalseDeparture() {
        adapter.handleProfileReported(event(false, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        verify(snapshotPersistence, never())
                .replaceCompleteParticipantSnapshot(anyString(), any(), anyLong(), anyString());
        // 资料字段仍须写入：成员不可信不代表资料不可信。
        verify(patchService).applyPatch(any());
    }

    @Test
    void profileFieldsUseSnapshotSourceSoEventsWinAtSameTime() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000002@s.whatsapp.net", null, "919000000002",
                        false, false, null))));

        ArgumentCaptor<GroupMetadataPatch> captor =
                ArgumentCaptor.forClass(GroupMetadataPatch.class);
        verify(patchService).applyPatch(captor.capture());
        GroupMetadataPatch patch = captor.getValue();
        assertThat(patch.source())
                .as("完整快照的可信度必须低于精确变更事件，否则迟到快照会压过新事件")
                .isEqualTo(GroupMetadataFieldSource.PROFILE_SNAPSHOT);
        assertThat(patch.fieldMask()).containsExactly(GroupMetadataPatchField.SUBJECT);
    }

    @Test
    void missingMembersMeanProfileOnly() {
        adapter.handleProfileReported(event(false, List.of()));

        verify(snapshotPersistence, never())
                .replaceCompleteParticipantSnapshot(anyString(), any(), anyLong(), anyString());
        verify(patchService).applyPatch(any());
    }

    @Test
    void tenantContextIsClearedAfterHandling() {
        adapter.handleProfileReported(event(false, List.of()));

        assertThat(TenantContext.get())
                .as("消费线程被复用，租户上下文必须归还")
                .isNull();
    }

    @Test
    void phoneAlreadyInJidFormIsNotSuffixedTwice() {
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000003@s.whatsapp.net", null, "919000000003@s.whatsapp.net",
                        false, false, null))));

        ArgumentCaptor<List<GroupParticipantResult>> captor = ArgumentCaptor.captor();
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue().get(0).pnJid())
                .as("协议侧已把号码还原成完整 JID 时不得再拼一次后缀："
                        + "绑定按 pn_jid 等值关联，双后缀会让受控账号永远匹配不上自己的群")
                .isEqualTo("919000000003@s.whatsapp.net");
    }

    @Test
    void profileReportRegistersGroupLinkSoTheGroupListShowsItImmediately() {
        // 群组列表主表是 group_link，建档不登记它，页面就要等精确关系事件——
        // 那条走另一个 topic，实测被上线全量清单堵了 5 分 49 秒。
        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000001@s.whatsapp.net", null, "919000000001", true, true, "superadmin"))));

        verify(groupLinkRegistryService).registerAccountObservedGroup(
                eq("120363-abc@g.us"), eq("Alpha"), eq(ProtocolBackend.WEB), anyLong());
    }

    @Test
    void groupLinkRegistrationFailureDoesNotDropTheProfileFacts() {
        // 登记失败不能连累资料与成员落库：那两者才是本事件的主载荷。
        org.mockito.Mockito.doThrow(new RuntimeException("registry down"))
                .when(groupLinkRegistryService)
                .registerAccountObservedGroup(anyString(), any(), any(), anyLong());

        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "919000000001@s.whatsapp.net", null, "919000000001", false, false, null))));

        verify(patchService).applyPatch(any(GroupMetadataPatch.class));
        verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), any(), anyLong(), anyString());
    }

    private static ProtocolGroupProfileReportedEvent event(
            boolean membersComplete, List<ProtocolGroupProfileReportedEvent.Member> members) {
        return event(membersComplete, members, null, null);
    }

    private static ProtocolGroupProfileReportedEvent event(
            boolean membersComplete,
            List<ProtocolGroupProfileReportedEvent.Member> members,
            Long groupCreatedAt) {
        return event(membersComplete, members, groupCreatedAt, null);
    }

    @Test
    void creatorPhoneIsPersistedSoTheListShowsCreatorAndFlag() {
        // 列表的「创建者」与国旗都来自这一个手机号：国旗按号码区号推导。
        org.mockito.Mockito.when(groupLinkRegistryService.registerAccountObservedGroup(
                anyString(), any(), any(), anyLong())).thenReturn(77L);

        adapter.handleProfileReported(event(true, List.of(), null, "923206788780"));

        verify(creatorWriter).writeCreator(eq(77L), eq("923206788780"), anyLong());
    }

    @Test
    void missingCreatorPhoneSkipsTheCompatibilityWrite() {
        adapter.handleProfileReported(event(true, List.of()));

        verify(creatorWriter, never()).writeCreator(anyLong(), anyString(), anyLong());
    }

    @Test
    void controlledAccountBindingsAreWrittenFromTheMemberList() {
        // 可用管理员与邀请码选号都 INNER JOIN wa_account_group_binding。建档带着完整成员与
        // 角色，却不建绑定，就会出现"群主在控端、列表却判不可用"。
        Account controlled = new Account();
        controlled.setId(1649L);
        controlled.setWsPhone("923048826465");
        org.mockito.Mockito.when(accountMapper.selectActiveByWsPhones(any()))
                .thenReturn(List.of(controlled));

        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "923048826465@s.whatsapp.net", null, "923048826465", true, true, "superadmin"),
                new ProtocolGroupProfileReportedEvent.Member(
                        "916360432840@s.whatsapp.net", null, "916360432840", false, false, null))));

        // 只给受控账号建绑定；外部号码查不到账号自然不写。
        verify(snapshotPersistence).applyControlledParticipantObservation(
                eq(1649L), eq("120363-abc@g.us"), eq(true), eq(true),
                anyLong(), anyString(), anyString());
    }

    @Test
    void bindingReconcileFailureDoesNotDropTheProfileFacts() {
        org.mockito.Mockito.when(accountMapper.selectActiveByWsPhones(any()))
                .thenThrow(new RuntimeException("account lookup down"));

        adapter.handleProfileReported(event(true, List.of(
                new ProtocolGroupProfileReportedEvent.Member(
                        "923048826465@s.whatsapp.net", null, "923048826465", true, true, "superadmin"))));

        verify(patchService).applyPatch(any(GroupMetadataPatch.class));
    }

    @Test
    void creationTimeIsPersistedSoTheListCanShowIt() {
        adapter.handleProfileReported(event(true, List.of(), 1_787_096_047_000L));

        verify(snapshotPersistence).fillGroupCreatedAt("120363-abc@g.us", 1_787_096_047_000L);
    }

    @Test
    void missingCreationTimeIsPassedThroughAsUnobserved() {
        // 未观察写 null 而不是 0：0 会被当成 1970 年建群。
        adapter.handleProfileReported(event(true, List.of()));

        verify(snapshotPersistence).fillGroupCreatedAt("120363-abc@g.us", null);
    }

    @Test
    void missingCreationTimeStillLocksGroupBeforeProfileAndMembers() {
        org.mockito.Mockito.when(groupLinkRegistryService.registerAccountObservedGroup(
                anyString(), any(), any(), anyLong())).thenReturn(77L);

        adapter.handleProfileReported(event(true, List.of()));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(snapshotPersistence, patchService);
        order.verify(snapshotPersistence)
                .lockGroupWriteBoundary(77L, "120363-abc@g.us");
        order.verify(snapshotPersistence)
                .fillGroupCreatedAt("120363-abc@g.us", null);
        order.verify(patchService).applyPatch(any(GroupMetadataPatch.class));
        order.verify(snapshotPersistence).replaceCompleteParticipantSnapshot(
                anyString(), any(), anyLong(), anyString());
    }

    private static ProtocolGroupProfileReportedEvent event(
            boolean membersComplete,
            List<ProtocolGroupProfileReportedEvent.Member> members,
            Long groupCreatedAt,
            String creatorPhone) {
        return new ProtocolGroupProfileReportedEvent(
                "acc-100:group.profile_reported:1",
                1L,
                100L,
                "protocol-account-100",
                "WEB",
                "120363-abc@g.us",
                List.of("subject"),
                "Alpha",
                null, null, null, null, null, null,
                members,
                membersComplete,
                "online_full_metadata",
                2_000L,
                groupCreatedAt,
                creatorPhone,
                "worker-1",
                null);
    }
}
