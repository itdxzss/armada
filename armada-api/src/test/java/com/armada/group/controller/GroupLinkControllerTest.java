package com.armada.group.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import com.armada.group.model.vo.GroupMemberOperationResultVO;
import com.armada.group.model.enums.GroupTimedMessageMode;
import com.armada.group.model.enums.GroupPermissionKey;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.model.vo.GroupAvatarUpdateVO;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupLinkMemberVO;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkPreviewItemVO;
import com.armada.group.model.vo.GroupMetadataSyncAcceptedVO;
import com.armada.group.model.vo.GroupCreatorLeaveCapabilityVO;
import com.armada.group.model.vo.GroupCreatorLeaveResultVO;
import com.armada.group.model.enums.GroupCreatorLeaveStatus;
import com.armada.group.service.FileLinesExtractor;
import com.armada.group.service.GroupBatchTaskService;
import com.armada.group.service.GroupDetailService;
import com.armada.group.service.GroupLinkImportService;
import com.armada.group.service.GroupLinkService;
import com.armada.group.service.GroupCreatorLeaveService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GroupLinkController 单测:只覆盖轻量路由委托,不启动数据库或协议层。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkControllerTest {

    @Mock
    private GroupLinkService groupLinkService;

    @Mock
    private GroupDetailService groupDetailService;

    @Mock
    private GroupLinkImportService importService;

    @Mock
    private FileLinesExtractor extractor;

    @Mock
    private GroupBatchTaskService batchTaskService;

    @Mock
    private GroupCreatorLeaveService creatorLeaveService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GroupLinkController(
                        groupLinkService, groupDetailService, importService, extractor,
                        batchTaskService, creatorLeaveService))
                .build();
    }

    @Test
    void creatorLeaveRoutesUseSharedService() throws Exception {
        when(creatorLeaveService.capability(10L))
                .thenReturn(new GroupCreatorLeaveCapabilityVO(true, null, null));
        when(creatorLeaveService.execute(10L, null))
                .thenReturn(new GroupCreatorLeaveResultVO(
                        GroupCreatorLeaveStatus.SUCCESS, "群主退群成功"));

        mockMvc.perform(get("/api/group-links/10/creator-leave-capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executable").value(true));
        mockMvc.perform(post("/api/group-links/10/creator-leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        verify(creatorLeaveService).capability(10L);
        verify(creatorLeaveService).execute(10L, null);
    }

    @Test
    void getMembers_delegatesToServiceAndReturnsApiResponse() throws Exception {
        GroupLinkMemberListVO vo = new GroupLinkMemberListVO(
                10L,
                "120363members@g.us",
                2,
                List.of(
                        new GroupLinkMemberVO(
                                "8613800000000@s.whatsapp.net", "8613800000000", true, true, "superadmin"),
                        new GroupLinkMemberVO(
                                "8613900000000@s.whatsapp.net", "8613900000000", false, false, null)));
        when(groupDetailService.members(10L)).thenReturn(vo);

        mockMvc.perform(get("/api/group-links/10/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groupLinkId").value(10))
                .andExpect(jsonPath("$.data.groupJid").value("120363members@g.us"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.members[0].jid").value("8613800000000@s.whatsapp.net"))
                .andExpect(jsonPath("$.data.members[0].phone").value("8613800000000"))
                .andExpect(jsonPath("$.data.members[0].admin").value(true))
                .andExpect(jsonPath("$.data.members[0].owner").value(true))
                .andExpect(jsonPath("$.data.members[0].role").value("superadmin"));

        verify(groupDetailService).members(10L);
    }

    @Test
    void getDetailDelegatesToDetailServiceAndReturnsRealtimeFields() throws Exception {
        GroupDetailVO vo = new GroupDetailVO(
                10L,
                "120363detail@g.us",
                "真实群名",
                "本地备注",
                "https://pps.whatsapp.net/current.jpg",
                true,
                null,
                "7d",
                new GroupDetailVO.Permissions(true, false, true, null, true),
                new GroupDetailVO.Capabilities(new GroupDetailVO.Capability(false, "Baileys 当前不支持")),
                true,
                null,
                List.of(new GroupLinkMemberVO(
                        "8613800000000@s.whatsapp.net", "8613800000000", true, true, "OWNER")),
                "SUCCEEDED",
                1_722_470_400_000L,
                null);
        when(groupDetailService.detail(10L)).thenReturn(vo);

        mockMvc.perform(get("/api/group-links/10/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groupName").value("真实群名"))
                .andExpect(jsonPath("$.data.timedMessageMode").value("7d"))
                .andExpect(jsonPath("$.data.permissions.editGroupSettings").value(true))
                .andExpect(jsonPath("$.data.permissions.sendMessages").value(false))
                .andExpect(jsonPath("$.data.capabilities.inviteViaLink.supported").value(false))
                .andExpect(jsonPath("$.data.members[0].owner").value(true));

        verify(groupDetailService).detail(10L);
    }

    @Test
    void postMetadataSyncReturnsAcceptedQueueState() throws Exception {
        when(groupDetailService.requestMetadataSync(10L))
                .thenReturn(new GroupMetadataSyncAcceptedVO(true, "PENDING"));

        mockMvc.perform(post("/api/group-links/10/metadata-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(groupDetailService).requestMetadataSync(10L);
    }

    @Test
    void patchProfile_delegatesToServiceAndReturnsApiResponse() throws Exception {
        mockMvc.perform(patch("/api/group-links/10")
                        .contentType("application/json")
                        .content("""
                                {
                                  "groupName": "运营群A",
                                  "remark": "重点客户",
                                  "avatarUrl": "https://cdn.example.test/group-a.jpg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupLinkService).updateProfile(eq(10L), argThat(dto ->
                dto != null
                        && "运营群A".equals(dto.groupName())
                        && "重点客户".equals(dto.remark())
                        && "https://cdn.example.test/group-a.jpg".equals(dto.avatarUrl())));
    }

    @Test
    void batchAssignFolderDelegatesNullableFolderId() throws Exception {
        when(groupLinkService.assignFolder(List.of(101L, 102L), null)).thenReturn(2);

        mockMvc.perform(post("/api/group-links/batch-assign-folder")
                        .contentType("application/json")
                        .content("{\"ids\":[101,102],\"folderId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        verify(groupLinkService).assignFolder(List.of(101L, 102L), null);
    }

    @Test
    void postSubject_delegatesToServiceAndReturnsApiResponse() throws Exception {
        mockMvc.perform(post("/api/group-links/10/subject")
                        .contentType("application/json")
                        .content("""
                                {
                                  "subject": "新群名"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupDetailService).updateSubject(eq(10L), argThat(dto ->
                dto != null && "新群名".equals(dto.subject())));
    }

    @Test
    void postTimedMessageDelegatesModeWithoutExecutionAccount() throws Exception {
        mockMvc.perform(post("/api/group-links/10/timed-message")
                        .contentType("application/json")
                        .content("{\"mode\":\"7d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupDetailService).updateTimedMessage(eq(10L), argThat(dto ->
                dto != null && dto.mode() == GroupTimedMessageMode.DAYS_7));
    }

    @Test
    void postSettingsDelegatesOneExplicitPermissionKey() throws Exception {
        mockMvc.perform(post("/api/group-links/10/settings")
                        .contentType("application/json")
                        .content("""
                                {
                                  "key": "ADD_MEMBERS",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupDetailService).updateSetting(eq(10L), argThat(dto ->
                dto != null
                        && dto.key() == GroupPermissionKey.ADD_MEMBERS
                        && dto.enabled()));
    }

    @Test
    void postMemberBatchRoutesDelegateToMatchingServiceMethods() throws Exception {
        GroupMemberBatchResultVO result = new GroupMemberBatchResultVO(
                true,
                false,
                "成员操作成功",
                List.of(new GroupMemberOperationResultVO(
                        "member@s.whatsapp.net", "OK", null)));
        when(groupDetailService.promoteMembers(eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);
        when(groupDetailService.demoteMembers(eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);
        when(groupDetailService.kickMembers(eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);

        for (String path : List.of("promote-batch", "demote-batch", "kick-batch")) {
            mockMvc.perform(post("/api/group-links/10/members/" + path)
                            .contentType("application/json")
                            .content("{\"jids\":[\"member@s.whatsapp.net\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(true))
                    .andExpect(jsonPath("$.data.results[0].status").value("OK"));
        }

        verify(groupDetailService).promoteMembers(eq(10L), argThat(dto ->
                dto.jids().equals(List.of("member@s.whatsapp.net"))));
        verify(groupDetailService).demoteMembers(eq(10L), org.mockito.ArgumentMatchers.any());
        verify(groupDetailService).kickMembers(eq(10L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void postDescription_delegatesToServiceAndReturnsApiResponse() throws Exception {
        mockMvc.perform(post("/api/group-links/10/description")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": 7,
                                  "description": "群描述"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupLinkService).updateDescription(eq(10L), argThat(dto ->
                dto != null && dto.accountId().equals(7L) && "群描述".equals(dto.description())));
    }

    @Test
    void postAnnouncementText_delegatesToServiceAndReturnsApiResponse() throws Exception {
        mockMvc.perform(post("/api/group-links/10/announcement-text")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": 7,
                                  "text": "群公告"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupLinkService).updateAnnouncementText(eq(10L), argThat(dto ->
                dto != null && dto.accountId().equals(7L) && "群公告".equals(dto.text())));
    }

    @Test
    void postPicture_delegatesToServiceAndReturnsApiResponse() throws Exception {
        mockMvc.perform(post("/api/group-links/10/picture")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": 7,
                                  "url": "https://cdn.example.test/group.jpg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupLinkService).updatePicture(eq(10L), argThat(dto ->
                dto != null
                        && dto.accountId().equals(7L)
                        && "https://cdn.example.test/group.jpg".equals(dto.url())
                        && dto.base64() == null));
    }

    @Test
    void postAvatarAcceptsMultipartAndReturnsMirrorState() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(groupDetailService.updateAvatar(eq(10L), argThat(upload ->
                upload != null && "avatar.jpg".equals(upload.getOriginalFilename()))))
                .thenReturn(new GroupAvatarUpdateVO(
                        true, true, "https://pps.whatsapp.net/new.jpg"));

        mockMvc.perform(multipart("/api/group-links/10/avatar").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.mirrorSynced").value(true))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://pps.whatsapp.net/new.jpg"));

        verify(groupDetailService).updateAvatar(eq(10L), argThat(upload ->
                upload != null && upload.getSize() == 3));
    }

    @Test
    void importLinks_passesOriginalFilenameToService() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "links.csv",
                "text/csv",
                "https://chat.whatsapp.com/ABC123".getBytes(StandardCharsets.UTF_8));
        when(extractor.extract(argThat(f -> f != null && "links.csv".equals(f.getOriginalFilename())),
                eq("https://chat.whatsapp.com/TEXT123"))).thenReturn(List.of(
                "https://chat.whatsapp.com/ABC123",
                "https://chat.whatsapp.com/TEXT123"));
        when(importService.importLinks(argThat(dto ->
                dto != null
                        && dto.labelId().equals(12L)
                        && dto.batchName().equals("批次A")
                        && dto.sourceFileName().equals("links.csv")
                        && dto.lines().size() == 2))).thenReturn(
                new GroupLinkImportResultVO(99L, 2, 2, 0, 0, 0, List.of()));

        mockMvc.perform(multipart("/api/group-links/import")
                        .file(file)
                        .param("labelId", "12")
                        .param("batchName", "批次A")
                        .param("text", "https://chat.whatsapp.com/TEXT123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(99));

        verify(importService).importLinks(argThat(dto ->
                dto != null && "links.csv".equals(dto.sourceFileName())));
    }

    @Test
    void postBatchPreview_delegatesToServiceAndReturnsApiResponse() throws Exception {
        GroupLinkPreviewBatchVO vo = new GroupLinkPreviewBatchVO(
                1,
                1,
                0,
                List.of(new GroupLinkPreviewItemVO(
                        10L,
                        "https://chat.whatsapp.com/ABC123",
                        true,
                        null,
                        "120363preview@g.us",
                        "预览群",
                        12,
                        false,
                        "8613999999999",
                        true,
                        "ABC123",
                        1_780_387_200_000L)));
        when(groupLinkService.previewBatch(argThat(dto ->
                dto != null && dto.accountId().equals(7L) && dto.ids().equals(List.of(10L))))).thenReturn(vo);

        mockMvc.perform(post("/api/group-links/batch-preview")
                        .contentType("application/json")
                        .content("{\"accountId\":7,\"ids\":[10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.items[0].groupLinkId").value(10))
                .andExpect(jsonPath("$.data.items[0].success").value(true))
                .andExpect(jsonPath("$.data.items[0].groupJid").value("120363preview@g.us"))
                .andExpect(jsonPath("$.data.items[0].ownerPhone").value("8613999999999"));

        verify(groupLinkService).previewBatch(argThat(dto ->
                dto != null && dto.accountId().equals(7L) && dto.ids().equals(List.of(10L))));
    }
}
