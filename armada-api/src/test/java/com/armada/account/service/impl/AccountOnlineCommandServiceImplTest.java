package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlinePlan;
import com.armada.account.service.AccountOnlineService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * 单账号自动分配代理上线命令服务单测。
 *
 * <p>只验证账号域编排:查账号/凭据/自动分配代理 → 组装 {@link AccountOnlinePlan}
 * → 调现有上线服务。协议 HTTP 行为由底层 adapter 测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountOnlineCommandServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountCredentialMapper credentialMapper;

    @Mock
    private IpProxyService ipProxyService;

    @Mock
    private AccountOnlineService accountOnlineService;

    @InjectMocks
    private AccountOnlineCommandServiceImpl service;

    @Test
    void online_validAccountCredentialAndAllocatedProxy_delegatesPlanAndMapsAcceptedVo() {
        Account account = new Account();
        account.setId(100L);
        account.setWsPhone("8613800138000");
        account.setProtocolAccountId("acc_8613800138000");
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(100L);
        credential.setCredFormat(2);
        credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
        ProxyEndpoint endpoint = new ProxyEndpoint(
                ProxyEndpoint.PROTOCOL_SOCKS5,
                "proxy.internal",
                1080,
                new ProxyCredentials("user", "pass_session-Abc123"),
                "印度");
        Instant syncedAt = Instant.parse("2026-06-26T10:15:30Z");
        OnlineAccepted accepted = new OnlineAccepted(
                "acc_8613800138000",
                true,
                StateSource.MANUAL_REFRESH,
                syncedAt,
                new OnlineRouting("worker-a", null, "worker-a", true));
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
        when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenReturn(accepted);

        AccountOnlineVO result = service.online(100L);

        verify(ipProxyService).allocateOnlineEndpoint(100L);
        ArgumentCaptor<AccountOnlinePlan> planCaptor = ArgumentCaptor.forClass(AccountOnlinePlan.class);
        verify(accountOnlineService).online(planCaptor.capture());
        AccountOnlinePlan plan = planCaptor.getValue();
        assertThat(plan.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(plan.credentialFormat()).isEqualTo(CredentialFormat.BAILEYS_JSON);
        assertThat(plan.credentialJson()).isEqualTo("{\"creds\":{},\"keys\":{}}");
        assertThat(plan.proxyEndpoint()).isSameAs(endpoint);

        assertThat(result.accountId()).isEqualTo(100L);
        assertThat(result.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(result.accepted()).isTrue();
        assertThat(result.stateSource()).isEqualTo("MANUAL_REFRESH");
        assertThat(result.syncedAt()).isEqualTo(syncedAt.toEpochMilli());
        assertThat(result.ownerWorkerId()).isEqualTo("worker-a");
        assertThat(result.ownerEndpoint()).isNull();
        assertThat(result.currentWorkerId()).isEqualTo("worker-a");
        assertThat(result.local()).isTrue();
    }

    @Test
    void online_success_logsSafeOperationalContextWithoutSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccountOnlineCommandServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Account account = new Account();
            account.setId(100L);
            account.setProtocolAccountId("acc_8613800138000");
            String credentialJson = "{\"creds\":{},\"keys\":{}}";
            AccountCredential credential = new AccountCredential();
            credential.setAccountId(100L);
            credential.setCredFormat(2);
            credential.setCredsJson(credentialJson);
            ProxyEndpoint endpoint = new ProxyEndpoint(
                    ProxyEndpoint.PROTOCOL_SOCKS5,
                    "proxy.internal",
                    1080,
                    new ProxyCredentials("user", "pass_session-Abc123"),
                    "印度");
            OnlineAccepted accepted = new OnlineAccepted(
                    "acc_8613800138000",
                    true,
                    StateSource.MANUAL_REFRESH,
                    Instant.parse("2026-06-26T10:15:30Z"),
                    new OnlineRouting("worker-a", null, "worker-a", true));
            when(accountMapper.selectActiveById(100L)).thenReturn(account);
            when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
            when(ipProxyService.allocateOnlineEndpoint(100L)).thenReturn(new IpProxyAllocation(7L, endpoint));
            when(accountOnlineService.online(any(AccountOnlinePlan.class))).thenReturn(accepted);

            service.online(100L);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线开始 accountId=100"));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线调用协议层 accountId=100 allocatedProxyId=7")
                            && message.contains("credentialFormat=BAILEYS_JSON")
                            && message.contains("credentialLength=" + credentialJson.length()));
            assertThat(messages)
                    .anyMatch(message -> message.contains("账号上线已受理 accountId=100 allocatedProxyId=7 accepted=true")
                            && message.contains("stateSource=MANUAL_REFRESH")
                            && message.contains("ownerWorkerId=worker-a")
                            && message.contains("local=true"));
            assertThat(messages)
                    .noneMatch(message -> message.contains(credentialJson)
                            || message.contains("pass_session-Abc123"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void online_missingAccount_throwsNotFoundBeforeCredentialLookup() {
        when(accountMapper.selectActiveById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.online(404L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
                    assertThat(ex.getMessage()).contains("账号不存在");
                });
        verify(credentialMapper, never()).selectByAccountId(any());
        verifyNoInteractions(ipProxyService, accountOnlineService);
    }

    @Test
    void online_missingCredential_throwsValidationBeforeProxyLookup() {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_8613800138000");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.online(100L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("账号凭据不存在");
                });
        verifyNoInteractions(ipProxyService, accountOnlineService);
    }
}
