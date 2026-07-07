package com.armada.marketing.controller;

import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.GroupCreationMarketingTaskService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GroupCreationMarketingTaskControllerTest {

    @Mock
    private GroupCreationMarketingTaskService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GroupCreationMarketingTaskController(service))
                .build();
    }

    @Test
    void createDelegatesToServiceAndReturnsDetail() throws Exception {
        when(service.createTask(argThat(request ->
                request != null
                        && "建群营销".equals(request.taskName())
                        && request.accountGroupId().equals(8L)
                        && request.marketingTemplateId().equals(18L)
                        && request.materials().size() == 1
                        && "a.txt".equals(request.materials().get(0).fileName()))))
                .thenReturn(new GroupCreationMarketingTaskDetailVO(
                        1L,
                        "建群营销",
                        8L,
                        "A组",
                        18L,
                        "模板",
                        null,
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        30,
                        "活动群",
                        null,
                        null,
                        1000L,
                        1000L,
                        List.of()));

        mockMvc.perform(post("/api/group-creation-marketing-tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"建群营销",
                                  "accountGroupId":8,
                                  "accountGroupName":"A组",
                                  "marketingTemplateId":18,
                                  "marketingTemplateName":"模板",
                                  "sendIntervalSeconds":45,
                                  "groupNamePrefix":"活动群",
                                  "materials":[{"fileName":"a.txt","content":"8613900000000"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.taskName").value("建群营销"))
                .andExpect(jsonPath("$.data.matchedItemCount").value(1));

        verify(service).createTask(argThat(request ->
                request != null
                        && request.accountGroupId().equals(8L)
                        && request.sendIntervalSeconds().equals(45)
                        && request.materials().get(0).content().equals("8613900000000")));
    }

    @Test
    void stopDelegatesToService() throws Exception {
        when(service.stopTask(7L)).thenReturn(1);

        mockMvc.perform(post("/api/group-creation-marketing-tasks/7/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));

        verify(service).stopTask(7L);
    }

    @Test
    void accountCandidatesDelegatesToServiceAndReturnsRows() throws Exception {
        GroupCreationMarketingAccountCandidate candidate = new GroupCreationMarketingAccountCandidate();
        candidate.setAccountId(7L);
        candidate.setAccountPhone("8613000000000");
        candidate.setProtocolAccountId("acc_7");
        candidate.setLoginState(2);
        when(service.accountCandidates(8L)).thenReturn(List.of(candidate));

        mockMvc.perform(get("/api/group-creation-marketing-tasks/account-candidates")
                        .param("accountGroupId", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].accountId").value(7))
                .andExpect(jsonPath("$.data[0].accountPhone").value("8613000000000"))
                .andExpect(jsonPath("$.data[0].loginState").value(2));

        verify(service).accountCandidates(8L);
    }
}
