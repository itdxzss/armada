package com.armada.group.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.dto.HistoricalGroupQuery;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import com.armada.group.service.HistoricalGroupService;
import com.armada.group.service.impl.HistoricalGroupAccountGroupQueryService;
import com.armada.group.service.impl.HistoricalGroupAccountGroupRefreshService;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class HistoricalGroupControllerTest {

    @Mock
    private HistoricalGroupService historicalGroupService;

    @Mock
    private HistoricalGroupAccountGroupQueryService queryService;

    @Mock
    private HistoricalGroupAccountGroupRefreshService refreshService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HistoricalGroupController(
                        historicalGroupService, queryService, refreshService))
                .build();
    }

    @Test
    void listUsesAccountGroupAndPaginationQueryAndReturnsPageResult() throws Exception {
        when(queryService.list(org.mockito.ArgumentMatchers.any())).thenReturn(PageResult.of(
                List.of(item(HistoricalGroupMembershipState.UNVERIFIED, null)),
                2,
                20,
                21));

        mockMvc.perform(get("/api/historical-groups")
                        .param("accountGroupId", "7")
                        .param("page", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.list[0].groupJid").value("120363history@g.us"))
                .andExpect(jsonPath("$.data.list[0].membershipState").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(21));

        verify(queryService).list(org.mockito.ArgumentMatchers.argThat(query ->
                query.getAccountGroupId().equals(7L)
                        && query.getPage() == 2
                        && query.getPageSize() == 20));
    }

    @Test
    void refreshUsesOnlyAccountGroupId() throws Exception {
        mockMvc.perform(post("/api/historical-groups/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountGroupId":8}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(refreshService).refresh(8L);
    }

    @Test
    void detailUsesExactQueryAndReturnsLinkAndFullMembers() throws Exception {
        HistoricalGroupDetailVO detail = new HistoricalGroupDetailVO(
                17L,
                "120363detail@g.us",
                "历史群详情",
                HistoricalGroupMembershipState.CURRENT_IN_GROUP,
                RoleCategory.ADMIN,
                HistoricalGroupSelfRole.ADMIN,
                SpeechState.NORMAL,
                2,
                false,
                "https://chat.whatsapp.com/current",
                true,
                true,
                null,
                null,
                null,
                List.of(new HistoricalGroupDetailVO.Member(
                        "8613800000017@s.whatsapp.net",
                        "8613800000017",
                        true,
                        false,
                        true,
                        HistoricalGroupSelfRole.ADMIN,
                        false,
                        "操作账号本人不能被降级或踢出")));
        when(historicalGroupService.getHistoricalGroupDetail(17L, "120363detail@g.us"))
                .thenReturn(detail);

        mockMvc.perform(get("/api/historical-groups/detail")
                        .param("accountGroupId", "17")
                        .param("groupJid", "120363detail@g.us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteUrl").value("https://chat.whatsapp.com/current"))
                .andExpect(jsonPath("$.data.linkAvailable").value(true))
                .andExpect(jsonPath("$.data.operationAllowed").value(true))
                .andExpect(jsonPath("$.data.members[0].participantJid")
                        .value("8613800000017@s.whatsapp.net"))
                .andExpect(jsonPath("$.data.members[0].phone").value("8613800000017"));

        verify(historicalGroupService).getHistoricalGroupDetail(17L, "120363detail@g.us");
    }

    @Test
    void participantEndpointsUseExactPathsAndBody() throws Exception {
        HistoricalGroupParticipantActionVO response = new HistoricalGroupParticipantActionVO(
                false,
                true,
                List.of(new HistoricalGroupParticipantActionVO.Result(
                        "8613800000099@s.whatsapp.net",
                        false,
                        "GROUP_PERMISSION_DENIED",
                        "GROUP_PERMISSION_DENIED",
                        "protocol complete error")));
        when(historicalGroupService.promoteParticipants(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);
        when(historicalGroupService.demoteParticipants(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);
        when(historicalGroupService.removeParticipants(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);
        String body = """
                {
                  "accountGroupId":17,
                  "groupJid":"120363detail@g.us",
                  "participantJids":["8613800000099@s.whatsapp.net"]
                }
                """;

        for (String action : List.of("promote", "demote", "remove")) {
            mockMvc.perform(post("/api/historical-groups/participants/" + action)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.partial").value(true))
                    .andExpect(jsonPath("$.data.results[0].participantJid")
                            .value("8613800000099@s.whatsapp.net"))
                    .andExpect(jsonPath("$.data.results[0].errorCode")
                            .value("GROUP_PERMISSION_DENIED"))
                    .andExpect(jsonPath("$.data.results[0].errorMessage")
                            .value("protocol complete error"));
        }

        verify(historicalGroupService).promoteParticipants(org.mockito.ArgumentMatchers.argThat(
                dto -> dto.accountGroupId().equals(17L)
                        && dto.groupJid().equals("120363detail@g.us")
                        && dto.participantJids().equals(List.of("8613800000099@s.whatsapp.net"))));
        verify(historicalGroupService).demoteParticipants(org.mockito.ArgumentMatchers.any());
        verify(historicalGroupService).removeParticipants(org.mockito.ArgumentMatchers.any());
    }

    private static HistoricalGroupItemVO item(
            HistoricalGroupMembershipState membershipState,
            SpeechState speechState) {
        return new HistoricalGroupItemVO(
                "120363history@g.us",
                "历史群",
                List.of(),
                null,
                null,
                null,
                null,
                null,
                membershipState,
                speechState == null ? null : RoleCategory.ADMIN,
                speechState == null ? null : HistoricalGroupSelfRole.ADMIN,
                speechState,
                speechState == null ? null : 20,
                speechState == null ? null : true,
                false,
                null,
                null);
    }
}
