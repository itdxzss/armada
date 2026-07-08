package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountLifecycleCommandService;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountService;
import com.armada.account.model.command.AccountLifecycleCommandItem;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.lang.reflect.Method;
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

    @Mock
    private AccountLifecycleCommandService accountLifecycleCommandService;

    @Mock
    private AccountOnlineAttemptLogService accountOnlineAttemptLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(
                        accountService,
                        accountGroupService,
                        accountOnlineCommandService,
                        accountLifecycleCommandService,
                        accountOnlineAttemptLogService))
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
    void postBatchOnline_withProtocolBackendsDelegatesAccountsToCommandService() throws Exception {
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
        List<AccountLifecycleCommandItem> commandItems = List.of(
                new AccountLifecycleCommandItem(100L, ProtocolBackend.WEB),
                new AccountLifecycleCommandItem(101L, ProtocolBackend.ANDROID));
        when(accountOnlineCommandService.onlineBatchWithProtocolBackends(eq(commandItems))).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/batch-online")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accounts": [
                                    {"id": 100, "protocolBackend": "WEB"},
                                    {"id": 101, "protocolBackend": "ANDROID"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.accepted").value(2));

        verify(accountOnlineCommandService).onlineBatchWithProtocolBackends(commandItems);
    }

    @Test
    void postRefreshStatus_delegatesToLifecycleServiceAndReturnsApiResponse() throws Exception {
        AccountStatusVO vo = new AccountStatusVO(
                100L,
                "acc_8613800138000",
                "ONLINE",
                "HEARTBEAT",
                "BUSINESS_STANDARD",
                1_782_446_400_000L,
                null,
                1_782_446_401_000L,
                false,
                null,
                "worker-a");
        when(accountLifecycleCommandService.refreshStatus(100L)).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/{id}/refresh-status", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accountId").value(100))
                .andExpect(jsonPath("$.data.protocolAccountId").value("acc_8613800138000"))
                .andExpect(jsonPath("$.data.state").value("ONLINE"))
                .andExpect(jsonPath("$.data.stateSource").value("HEARTBEAT"))
                .andExpect(jsonPath("$.data.workerId").value("worker-a"));

        verify(accountLifecycleCommandService).refreshStatus(100L);
    }

    @Test
    void postProbe_delegatesToLifecycleServiceAndReturnsApiResponse() throws Exception {
        AccountProbeVO vo = new AccountProbeVO(
                100L,
                "acc_8613800138000",
                true,
                1_782_446_460_000L,
                186L,
                "OK");
        when(accountLifecycleCommandService.probe(100L)).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/{id}/probe", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accountId").value(100))
                .andExpect(jsonPath("$.data.protocolAccountId").value("acc_8613800138000"))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.latencyMs").value(186))
                .andExpect(jsonPath("$.data.reasonCode").value("OK"));

        verify(accountLifecycleCommandService).probe(100L);
    }

    @Test
    void getOnlineAttempts_usesNumericAccountIdRouteConstraint() throws Exception {
        Method method = AccountController.class.getMethod("onlineAttempts", Long.class, int.class);

        assertThat(method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .containsExactly("/{id:\\d+}/online-attempts");
    }

    @Test
    void getOnlineAttempts_delegatesToAttemptLogServiceAndReturnsApiResponse() throws Exception {
        AccountOnlineAttemptLogVO vo = attemptLog(100L, "oa_recent", 4035L, "PROXY_CONNECT_FAILED");
        when(accountOnlineAttemptLogService.recentByAccount(100L, 7)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/accounts/100/online-attempts")
                        .param("limit", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].accountId").value(100))
                .andExpect(jsonPath("$.data[0].onlineAttemptId").value("oa_recent"))
                .andExpect(jsonPath("$.data[0].proxyId").value(4035))
                .andExpect(jsonPath("$.data[0].diagnosisCode").value("PROXY_CONNECT_FAILED"));

        verify(accountOnlineAttemptLogService).recentByAccount(100L, 7);
    }

    @Test
    void getOnlineAttemptTimeline_delegatesToAttemptLogServiceAndReturnsApiResponse() throws Exception {
        AccountOnlineAttemptLogVO vo = attemptLog(100L, "oa_1", 4036L, "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
        when(accountOnlineAttemptLogService.timeline("oa_1", 8)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/accounts/online-attempts/oa_1")
                        .param("limit", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].accountId").value(100))
                .andExpect(jsonPath("$.data[0].onlineAttemptId").value("oa_1"))
                .andExpect(jsonPath("$.data[0].proxyId").value(4036))
                .andExpect(jsonPath("$.data[0].diagnosisCode").value("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE"));

        verify(accountOnlineAttemptLogService).timeline("oa_1", 8);
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

    @Test
    void postBatchOffline_withProtocolBackendsDelegatesAccountsToCommandService() throws Exception {
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
        List<AccountLifecycleCommandItem> commandItems = List.of(
                new AccountLifecycleCommandItem(100L, ProtocolBackend.WEB),
                new AccountLifecycleCommandItem(101L, ProtocolBackend.ANDROID));
        when(accountOnlineCommandService.offlineBatchWithProtocolBackends(eq(commandItems))).thenReturn(vo);

        mockMvc.perform(post("/api/accounts/batch-offline")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accounts": [
                                    {"id": 100, "protocolBackend": "WEB"},
                                    {"id": 101, "protocolBackend": "ANDROID"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.accepted").value(2));

        verify(accountOnlineCommandService).offlineBatchWithProtocolBackends(commandItems);
    }

    private static AccountOnlineAttemptLogVO attemptLog(Long accountId,
                                                        String onlineAttemptId,
                                                        Long proxyId,
                                                        String diagnosisCode) {
        return new AccountOnlineAttemptLogVO(
                11L,
                accountId,
                "acc_8613800138000",
                onlineAttemptId,
                null,
                "cmd_1",
                "batch_1",
                proxyId,
                "batch_online",
                "VERIFYING",
                "PROXY_FAILED",
                diagnosisCode,
                "PROXY_OR_WA_CONNECTIVITY",
                408,
                "reason",
                "RETRYABLE",
                "MARK_PROXY_FAILED_RELEASE_SLOT",
                "worker-a",
                "{\"wsOpen\":false}",
                1_782_987_480_123L,
                1_782_987_481_123L);
    }
}
