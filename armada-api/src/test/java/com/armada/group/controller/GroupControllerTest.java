package com.armada.group.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.vo.GroupCreateParticipantVO;
import com.armada.group.model.vo.GroupCreateVO;
import com.armada.group.service.GroupOperationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock
    private GroupOperationService groupOperationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GroupController(groupOperationService))
                .build();
    }

    @Test
    void createGroupDelegatesToServiceAndReturnsApiResponse() throws Exception {
        when(groupOperationService.createGroup(argThat(dto ->
                dto != null
                        && dto.accountId().equals(7L)
                        && "测试群".equals(dto.subject())
                        && dto.participants().equals(List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net")))))
                .thenReturn(new GroupCreateVO(
                        "120363create@g.us",
                        false,
                        List.of(
                                new GroupCreateParticipantVO(
                                        "8613900000000@s.whatsapp.net", "OK", "200"),
                                new GroupCreateParticipantVO(
                                        "8613911111111@s.whatsapp.net", "PRIVACY_BLOCKED", "403"))));

        mockMvc.perform(post("/api/groups/create")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": 7,
                                  "subject": "测试群",
                                  "participants": [
                                    "+86 139-0000-0000",
                                    "8613911111111@s.whatsapp.net"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groupJid").value("120363create@g.us"))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.results[0].jid").value("8613900000000@s.whatsapp.net"))
                .andExpect(jsonPath("$.data.results[0].status").value("OK"))
                .andExpect(jsonPath("$.data.results[1].status").value("PRIVACY_BLOCKED"));

        verify(groupOperationService).createGroup(argThat(dto ->
                dto != null
                        && dto.accountId().equals(7L)
                        && "测试群".equals(dto.subject())
                        && dto.participants().size() == 2));
    }
}
