package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountService;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountState;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationResultReportedEvent;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NormalGroupCreationProtocolResultServiceTest {

    private final NormalGroupCreationMapper mapper =
            org.mockito.Mockito.mock(NormalGroupCreationMapper.class);
    private final NormalGroupCreationCommandDispatcher dispatcher =
            org.mockito.Mockito.mock(NormalGroupCreationCommandDispatcher.class);
    private final GroupParticipantPort participantPort =
            org.mockito.Mockito.mock(GroupParticipantPort.class);
    private final GroupLinkRegistryService registry =
            org.mockito.Mockito.mock(GroupLinkRegistryService.class);
    private final GroupLinkService groupLinkService =
            org.mockito.Mockito.mock(GroupLinkService.class);
    private final GroupMetadataSyncTaskService metadataSyncTaskService =
            org.mockito.Mockito.mock(GroupMetadataSyncTaskService.class);
    private final AccountService accountService =
            org.mockito.Mockito.mock(AccountService.class);
    private final AccountStateEventService accountStateEventService =
            org.mockito.Mockito.mock(AccountStateEventService.class);
    private final AccountStateMapper accountStateMapper =
            org.mockito.Mockito.mock(AccountStateMapper.class);
    private final NormalGroupCreationProtocolResultService service =
            new NormalGroupCreationProtocolResultService(
                    mapper, dispatcher, participantPort, registry, groupLinkService,
                    metadataSyncTaskService, accountService, accountStateEventService,
                    accountStateMapper);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void contactPrepare_waitsUntilEveryDirectionSettlesBeforeCreatingGroup() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"), eq("SUCCESS"),
                isNull(), isNull(), anyLong())).thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator", "SUCCESS",
                382L, "creator-web", "WEB", 31L,
                "CREATOR_SAVE_MEMBER", null, null, null));

        verify(dispatcher, never()).enqueueCreatorAction(item, "GROUP_CREATE");
        verify(mapper, never()).startGroupCreate(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void contactPrepare_lastSuccessEnqueuesCreateWithCreatorBackend() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("cmd-contact-member"), eq("SUCCESS"),
                isNull(), isNull(), anyLong())).thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(0);
        when(dispatcher.enqueueCreatorAction(item, "GROUP_CREATE")).thenReturn("cmd-create");
        when(mapper.startGroupCreate(
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq("cmd-create"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-member", "SUCCESS",
                383L, "member-android", "ANDROID", 31L,
                "MEMBER_SAVE_CREATOR", null, null, null));

        verify(dispatcher).enqueueCreatorAction(item, "GROUP_CREATE");
        verify(mapper).startGroupCreate(
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq("cmd-create"), anyLong());
    }

    @Test
    void contactPrepare_lastDirectionFailureStillEnqueuesCreate() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("cmd-contact-member"), eq("FAILED"),
                eq("CONTACT_PREPARE_REJECTED"), eq("好友准备被拒绝"), anyLong())).thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(0);
        when(dispatcher.enqueueCreatorAction(item, "GROUP_CREATE")).thenReturn("cmd-create");
        when(mapper.startGroupCreate(eq(21L), eq("cmd-create"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-member", "FAILED",
                383L, "member-android", "ANDROID", 31L,
                "MEMBER_SAVE_CREATOR", null, "CONTACT_PREPARE_REJECTED", "好友准备被拒绝"));

        verify(mapper).startGroupCreate(eq(21L), eq("cmd-create"), anyLong());
        verify(mapper, never()).failProtocolAction(
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyLong());
    }

    @Test
    void contactPrepare_unknownOutcomeIsSettledAndNeverBlocksCreate() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"), eq("UNKNOWN"),
                eq("TIMEOUT"), eq("协议动作未成功，原因码：TIMEOUT"), anyLong()))
                .thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(0);
        when(dispatcher.enqueueCreatorAction(item, "GROUP_CREATE")).thenReturn("cmd-create");
        when(mapper.startGroupCreate(eq(21L), eq("cmd-create"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator", "UNKNOWN",
                382L, "creator-web", "WEB", 31L,
                "CREATOR_SAVE_MEMBER", null, "TIMEOUT", null));

        verify(mapper).startGroupCreate(eq(21L), eq("cmd-create"), anyLong());
    }

    @Test
    void contactPrepare_memberOfflinePersistsMemberFailureWithoutFailingTheItem() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("cmd-contact-member"), eq("FAILED"),
                eq("ACCOUNT_NOT_ONLINE"),
                eq("成员账号当前不在线，请将对应成员账号重新上线后重试"), anyLong()))
                .thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-member", "FAILED",
                383L, "member-android", "ANDROID", 31L,
                "MEMBER_SAVE_CREATOR", null, "ACCOUNT_NOT_ONLINE",
                "Protocol account has no owner worker"));

        verify(mapper).applyContactResult(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("cmd-contact-member"), eq("FAILED"),
                eq("ACCOUNT_NOT_ONLINE"),
                eq("成员账号当前不在线，请将对应成员账号重新上线后重试"), anyLong());
        verify(mapper, never()).failProtocolAction(
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyLong());
        verify(mapper, never()).startGroupCreate(
                anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
        verify(accountStateEventService).applyStateChanged(new AccountStateChangedEvent(
                1L, 383L, "member-android", null, "OFFLINE", 1000L,
                "NORMAL_GROUP_ACCOUNT_NOT_ONLINE", null, "normal_group_creation", null));
    }

    @Test
    void contactPrepare_creatorOfflinePersistsCreatorFailureWithoutFailingTheItem() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"), eq("FAILED"),
                eq("ACCOUNT_NOT_ONLINE"),
                eq("建群账号当前不在线，请重新上线后重试"), anyLong())).thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator", "FAILED",
                382L, "creator-web", "WEB", 31L,
                "CREATOR_SAVE_MEMBER", null, "ACCOUNT_NOT_ONLINE",
                "Protocol account has no owner worker"));

        verify(mapper).applyContactResult(
                eq(31L), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"), eq("FAILED"),
                eq("ACCOUNT_NOT_ONLINE"),
                eq("建群账号当前不在线，请重新上线后重试"), anyLong());
        verify(mapper, never()).failProtocolAction(
                anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyLong());
        verify(accountStateEventService).applyStateChanged(new AccountStateChangedEvent(
                1L, 382L, "creator-web", null, "OFFLINE", 1000L,
                "NORMAL_GROUP_ACCOUNT_NOT_ONLINE", null, "normal_group_creation", null));
    }

    @Test
    void contactPrepare_duplicateResultDoesNotAdvanceOrFailTheItem() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);
        when(mapper.applyContactResult(
                eq(31L), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"), eq("SUCCESS"),
                isNull(), isNull(), anyLong())).thenReturn(0);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator", "SUCCESS",
                382L, "creator-web", "WEB", 31L,
                "CREATOR_SAVE_MEMBER", null, null, null));

        verify(mapper, never()).countPendingContactDirections(anyLong());
        verify(dispatcher, never()).enqueueCreatorAction(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    void contactPrepare_resultFromReplacedCommandIsIgnoredAfterRetry() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = new MemberWork(
                31L, 383L, "member-android", "ANDROID", "922",
                "PENDING", "SUCCESS",
                "cmd-contact-creator-retry", "cmd-contact-member", "PENDING");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator-old", "SUCCESS",
                382L, "creator-web", "WEB", 31L,
                "CREATOR_SAVE_MEMBER", null, null, null));

        verify(mapper, never()).applyContactResult(
                anyLong(), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator-old"),
                eq("SUCCESS"), isNull(), isNull(), anyLong());
        verify(dispatcher, never()).enqueueCreatorAction(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void contactPrepare_currentCommandWithWrongBackendIsRejected() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        MemberWork member = member();
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberWorkForUpdate(1L, 21L, 31L)).thenReturn(member);

        assertThatThrownBy(() -> service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "cmd-contact-creator", "SUCCESS",
                382L, "creator-web", "ANDROID", 31L,
                "CREATOR_SAVE_MEMBER", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议后端不匹配");

        verify(mapper, never()).applyContactResult(
                anyLong(), eq("CREATOR_SAVE_MEMBER"), eq("cmd-contact-creator"),
                eq("SUCCESS"), isNull(), isNull(), anyLong());
    }

    @Test
    void groupCreate_promotesFrozenSecondaryAdminsThroughExistingParticipantPortThenStartsSettings() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectSecondaryAdminWorks(21L)).thenReturn(List.of(
                secondaryAdmin(41L, 384L, "933")));
        when(participantPort.updateParticipants(
                eq(new ProtocolAccountRef(382L, ProtocolBackend.WEB, "creator-web", "911")),
                eq("120363001@g.us"), eq(List.of("933@s.whatsapp.net")),
                eq(GroupParticipantAction.PROMOTE)))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "933@s.whatsapp.net", "OK", "200"))));
        when(dispatcher.enqueueCreatorAction(item, "GROUP_SETTINGS_APPLY"))
                .thenReturn("cmd-settings");
        when(mapper.startGroupSettings(
                eq(21L), eq("cmd-create"), eq("cmd-settings"),
                eq("120363001@g.us"), eq("普群001"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "SUCCESS",
                382L, "creator-web", "WEB", null, null,
                "120363001@g.us", null, null));

        verify(participantPort).updateParticipants(
                eq(new ProtocolAccountRef(382L, ProtocolBackend.WEB, "creator-web", "911")),
                eq("120363001@g.us"), eq(List.of("933@s.whatsapp.net")),
                eq(GroupParticipantAction.PROMOTE));
        verify(mapper).startGroupSettings(
                eq(21L), eq("cmd-create"), eq("cmd-settings"),
                eq("120363001@g.us"), eq("普群001"), anyLong());
        verify(mapper).markParticipantsCreated(eq(21L), anyLong());
        verify(mapper).markSecondaryParticipantsCreated(eq(21L), anyLong());
        verify(mapper).markSecondaryAdminsPromoted(eq(21L), anyLong());
    }

    @Test
    void secondaryContactResultMapsExistingProtocolDirectionBackToInternalDirection() {
        ItemWork item = item("PREPARING_CONTACTS", null, null, null, null, "KEEP");
        SecondaryAdminWork secondary = secondaryAdmin(41L, 384L, "933");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectSecondaryAdminWorkForUpdate(1L, 21L, 41L)).thenReturn(secondary);
        when(mapper.applySecondaryContactResult(
                eq(41L), eq("SECONDARY_SAVE_ANCHOR"), eq("c3"), eq("SUCCESS"),
                isNull(), isNull(), anyLong())).thenReturn(1);
        when(mapper.countPendingContactDirections(21L)).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "CONTACT_PREPARE", "c3", "SUCCESS",
                384L, "acc_384", "WEB", 41L,
                "CREATOR_SAVE_MEMBER", null, null, null));

        verify(mapper).applySecondaryContactResult(
                eq(41L), eq("SECONDARY_SAVE_ANCHOR"), eq("c3"), eq("SUCCESS"),
                isNull(), isNull(), anyLong());
    }

    @Test
    void groupCreate_secondaryAdminPromotionFailureKeepsCreatedGroupAndReturnsAccountReason() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "LEAVE");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectSecondaryAdminWorks(21L)).thenReturn(List.of(
                secondaryAdmin(41L, 384L, "933")));
        when(participantPort.updateParticipants(
                org.mockito.ArgumentMatchers.any(ProtocolAccountRef.class),
                eq("120363001@g.us"), eq(List.of("933@s.whatsapp.net")),
                eq(GroupParticipantAction.PROMOTE)))
                .thenReturn(new GroupParticipantBatchResult(true, List.of(
                        new GroupParticipantBatchResult.Item(
                                "933@s.whatsapp.net", "FAILED", "403"))));
        when(mapper.failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"),
                eq("CREATED_PARTIAL"), eq("SECONDARY_ADMIN_PROMOTION_FAILED"),
                eq("群已创建，但次管理员设置失败：账号 384（协议状态 FAILED，原始状态 403）"),
                eq("120363001@g.us"), eq("evt-1"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "SUCCESS",
                382L, "creator-web", "WEB", null, null,
                "120363001@g.us", null, null));

        verify(mapper).markSecondaryAdminPromotionFailures(
                eq(21L), eq(List.of("933")),
                eq("SECONDARY_ADMIN_PROMOTION_FAILED"),
                eq("群已创建，但次管理员设置失败：账号 384（协议状态 FAILED，原始状态 403）"),
                anyLong());
        verify(mapper).failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"),
                eq("CREATED_PARTIAL"), eq("SECONDARY_ADMIN_PROMOTION_FAILED"),
                eq("群已创建，但次管理员设置失败：账号 384（协议状态 FAILED，原始状态 403）"),
                eq("120363001@g.us"), eq("evt-1"), anyLong());
        verify(dispatcher, never()).enqueueCreatorAction(item, "GROUP_LEAVE");
    }

    @Test
    void groupCreate_blankTemplateFinalizesSubjectFromFrozenPrefixAndGroupJid() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP",
                "", "ABCDEFGHI");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(dispatcher.enqueueCreatorAction(item, "GROUP_SETTINGS_APPLY"))
                .thenReturn("cmd-settings");
        when(mapper.startGroupSettings(
                eq(21L), eq("cmd-create"), eq("cmd-settings"),
                eq("120363000001234@g.us"), eq("ABCDEFGHI01234"), anyLong()))
                .thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "SUCCESS",
                382L, "creator-web", "WEB", null, null,
                "120363000001234@g.us", null, null));

        verify(mapper).startGroupSettings(
                eq(21L), eq("cmd-create"), eq("cmd-settings"),
                eq("120363000001234@g.us"), eq("ABCDEFGHI01234"), anyLong());
    }

    @Test
    void androidSettingsSuccessEnqueuesMetadataSyncForInviteLink() {
        ItemWork item = new ItemWork(
                21L, 1L, 9L, "普群001", "普群{no}",
                382L, "creator-android", "ANDROID", "911",
                "120363001@g.us", "RUNNING", "APPLYING_SETTINGS", "SENT",
                null, "cmd-settings", null,
                "KEEP", null, 91L, 92L,
                true, true, true, false, 0);
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectItemWork(21L)).thenReturn(item);
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of(member()));
        when(registry.registerSelfBuiltGroup(
                eq("120363001@g.us"), eq("普群001"), eq(382L), eq("911"),
                eq(2), anyLong())).thenReturn(101L);
        when(mapper.updateGroupLink(eq(21L), eq(101L), anyLong())).thenReturn(1);
        when(mapper.completeProtocolFlow(
                eq(21L), eq("APPLYING_SETTINGS"), eq("cmd-settings"), eq("SKIPPED"),
                eq("evt-1"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_SETTINGS_APPLY", "cmd-settings", "SUCCESS",
                382L, "creator-android", "ANDROID", null, null,
                null, null, null));

        verify(metadataSyncTaskService).enqueue(
                eq(101L), eq(GroupMetadataSyncTrigger.BASELINE_CAPTURED), anyLong());
    }

    @Test
    void groupCreate_partialFailureIsNeverMarkedCreatedAndMigratesToSuccessGroup() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("CREATED_PARTIAL"),
                eq("PARTICIPANTS_NOT_CONFIRMED"), eq("部分成员未确认"),
                eq("120363001@g.us"), eq("evt-1"), anyLong())).thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "FAILED",
                382L, "creator-web", "WEB", null, null,
                "120363001@g.us", "PARTICIPANTS_NOT_CONFIRMED", "部分成员未确认"));

        verify(mapper).failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("CREATED_PARTIAL"),
                eq("PARTICIPANTS_NOT_CONFIRMED"), eq("部分成员未确认"),
                eq("120363001@g.us"), eq("evt-1"), anyLong());
        verify(accountService).migrateGroup(List.of(382L), 91L);
        verify(accountStateEventService, never()).applyStateChanged(
                org.mockito.ArgumentMatchers.any(AccountStateChangedEvent.class));
        verify(mapper, never()).completeProtocolFlow(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void groupCreate_unknownWithAbnormalAccountStateBecomesFailed() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP");
        AccountState state = new AccountState();
        state.setAccountState(3);
        state.setLoginState(2);
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(accountStateMapper.selectByAccountId(382L)).thenReturn(state);
        when(mapper.failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("FAILED"),
                eq("ACCOUNT_ABNORMAL_DURING_CREATE"),
                eq("建群号状态异常，建群结果未确认"), isNull(), eq("evt-1"), anyLong()))
                .thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "UNKNOWN",
                382L, "creator-web", "WEB", null, null,
                null, "PROTOCOL_RESULT_UNCONFIRMED", "Android 建群结果未确认"));

        verify(mapper).failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("FAILED"),
                eq("ACCOUNT_ABNORMAL_DURING_CREATE"),
                eq("建群号状态异常，建群结果未确认"), isNull(), eq("evt-1"), anyLong());
    }

    @Test
    void groupCreate_unknownWithOfflineAccountBecomesFailed() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP");
        AccountState state = new AccountState();
        state.setAccountState(2);
        state.setLoginState(2);
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(accountStateMapper.selectByAccountId(382L)).thenReturn(state);
        when(mapper.failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("FAILED"),
                eq("ACCOUNT_OFFLINE_DURING_CREATE"),
                eq("建群号离线，建群结果未确认"), isNull(), eq("evt-1"), anyLong()))
                .thenReturn(1);

        service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "UNKNOWN",
                382L, "creator-web", "WEB", null, null,
                null, "PROTOCOL_RESULT_UNCONFIRMED", "Android 建群结果未确认"));

        verify(mapper).failProtocolAction(
                eq(21L), eq("CREATING_GROUP"), eq("cmd-create"), eq("FAILED"),
                eq("ACCOUNT_OFFLINE_DURING_CREATE"),
                eq("建群号离线，建群结果未确认"), isNull(), eq("evt-1"), anyLong());
    }

    @Test
    void resultWithWrongProtocolBackendIsRejectedBeforeStateMutation() {
        ItemWork item = item(
                "CREATING_GROUP", "cmd-create", null, null, null, "KEEP");
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);

        assertThatThrownBy(() -> service.handleNormalGroupCreationResult(event(
                "GROUP_CREATE", "cmd-create", "SUCCESS",
                382L, "creator-web", "ANDROID", null, null,
                "120363001@g.us", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议后端不匹配");

        verify(dispatcher, never()).enqueueCreatorAction(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).startGroupSettings(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static ItemWork item(
            String step,
            String createCommandId,
            String settingsCommandId,
            String leaveCommandId,
            String groupJid,
            String leavePolicy) {
        return item(step, createCommandId, settingsCommandId, leaveCommandId,
                groupJid, leavePolicy, "普群{no}", "普群001");
    }

    private static ItemWork item(
            String step,
            String createCommandId,
            String settingsCommandId,
            String leaveCommandId,
            String groupJid,
            String leavePolicy,
            String groupNameTemplate,
            String groupSubject) {
        return new ItemWork(
                21L, 1L, 9L, groupSubject, groupNameTemplate,
                382L, "creator-web", "WEB", "911",
                groupJid, "RUNNING", step, "SENT",
                createCommandId, settingsCommandId, leaveCommandId,
                leavePolicy, null, 91L, 92L,
                true, true, true, false, 0);
    }

    private static MemberWork member() {
        return new MemberWork(
                31L, 383L, "member-android", "ANDROID", "922",
                "PENDING", "PENDING",
                "cmd-contact-creator", "cmd-contact-member", "PENDING");
    }

    private static com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork
            secondaryAdmin(Long id, Long accountId, String phone) {
        return new com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork(
                id, accountId, "acc_" + accountId, "WEB", phone,
                383L, "member-android", "ANDROID", "922",
                "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS",
                "c1", "c2", "c3", "c4", "CONFIRMED", "PENDING", null);
    }

    private static ProtocolNormalGroupCreationResultReportedEvent event(
            String action,
            String commandId,
            String outcome,
            Long accountId,
            String protocolAccountId,
            String backend,
            Long memberId,
            String direction,
            String groupJid,
            String reasonCode,
            String reasonMessage) {
        return new ProtocolNormalGroupCreationResultReportedEvent(
                "evt-1", 1L, 9L, 21L, memberId, direction, action,
                accountId, protocolAccountId, backend, commandId, 1, outcome,
                groupJid, reasonCode, reasonMessage, false, 1000L, "worker-1");
    }
}
