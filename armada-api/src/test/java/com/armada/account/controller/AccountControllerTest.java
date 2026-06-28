package com.armada.account.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AccountController 单测:只覆盖轻量路由委托,不启动数据库或协议层。
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    @Mock
    private AccountGroupService accountGroupService;

    @Mock
    private AccountOnlineCommandService accountOnlineCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(accountService, accountGroupService, accountOnlineCommandService))
                .build();
    }

    @Test
    void postOnline_delegatesToCommandServiceAndReturnsApiResponse() throws Exception {
        AccountOnlineVO vo = new AccountOnlineVO(
                100L,
                "acc_8613800138000",
                true,
                "MANUAL_REFRESH",
                1_782_468_930_000L,
                "worker-a",
                null,
                "worker-a",
                true);
        when(accountOnlineCommandService.online(100L)).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/{id}/online", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accountId").value(100))
                .andExpect(jsonPath("$.data.protocolAccountId").value("acc_8613800138000"))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.ownerWorkerId").value("worker-a"));

        verify(accountOnlineCommandService).online(100L);
    }

    @Test
    void postBatchOnline_delegatesToCommandServiceAndReturnsApiResponse() throws Exception {
        AccountBatchOnlineVO vo = new AccountBatchOnlineVO(
                2,
                2,
                1,
                1,
                0,
                0,
                0,
                80L,
                List.of(
                        new AccountBatchOnlineItemVO(100L, "acc_100", "ACCEPTED", null, null),
                        new AccountBatchOnlineItemVO(101L, "acc_101", "TIMEOUT", 5000, null)),
                List.of());
        when(accountOnlineCommandService.onlineBatch(List.of(100L, 101L))).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/batch-online")
                        .contentType("application/json")
                        .content("{\"ids\":[100,101]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.submitted").value(2))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.timeout").value(1))
                .andExpect(jsonPath("$.data.results[0].accountId").value(100))
                .andExpect(jsonPath("$.data.results[1].result").value("TIMEOUT"));

        verify(accountOnlineCommandService).onlineBatch(List.of(100L, 101L));
    }

    @Test
    void postBatchOffline_delegatesToCommandServiceAndReturnsApiResponse() throws Exception {
        AccountBatchOnlineVO vo = new AccountBatchOnlineVO(
                2,
                2,
                2,
                0,
                0,
                0,
                0,
                0L,
                List.of(
                        new AccountBatchOnlineItemVO(100L, "acc_100", "ACCEPTED", null, null),
                        new AccountBatchOnlineItemVO(101L, "acc_101", "ACCEPTED", null, null)),
                List.of());
        when(accountOnlineCommandService.offlineBatch(List.of(100L, 101L))).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/batch-offline")
                        .contentType("application/json")
                        .content("{\"ids\":[100,101]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.submitted").value(2))
                .andExpect(jsonPath("$.data.accepted").value(2))
                .andExpect(jsonPath("$.data.results[0].accountId").value(100))
                .andExpect(jsonPath("$.data.results[1].protocolAccountId").value("acc_101"));

        verify(accountOnlineCommandService).offlineBatch(List.of(100L, 101L));
    }
}
