package com.armada.account.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountService;
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
}
