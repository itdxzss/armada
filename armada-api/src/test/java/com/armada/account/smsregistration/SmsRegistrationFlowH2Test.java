package com.armada.account.smsregistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.SmsActivationConverter;
import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.mapper.SmsRegistrationMapper;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.entity.SmsRegistration;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.enums.SmsRegistrationFailure;
import com.armada.account.model.enums.SmsRegistrationState;
import com.armada.account.service.AccountRegistrationService;
import com.armada.account.service.impl.SmsActivationServiceImpl;
import com.armada.account.service.impl.SmsRegistrationServiceImpl;
import com.armada.account.service.impl.SmsRegistrationWorker;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.platform.proxy.ProxyResolver;
import com.armada.platform.registration.go.GoRegistrationClient;
import com.armada.platform.registration.go.GoRegistrationRequest;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.armada.platform.registration.go.GoRegistrationResponse;
import com.armada.platform.registration.go.GoRegistrationResponse.Kind;
import com.armada.platform.registration.go.GoRegistrationSnapshot;
import com.armada.platform.registration.go.GoRegistrationState;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/**
 * 接码订单 Go 注册办理的端到端流程：真实 H2 Mapper、真实订单/办理服务和真实 worker，
 * 只替身掉 Go、号商、代理池和国家解析。证明状态推进、幂等重放、占用互斥与租户隔离，不代表真实注册。
 */
@SpringJUnitConfig(SmsRegistrationFlowH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class SmsRegistrationFlowH2Test {
    private static final long TENANT = 7L;
    private static final String TX = "2de772cb-f034-4cc2-8a0b-fbe218bcc01b";
    private static final String PROXY_URL = "socks5://user:pw_session-abc@gw.example:1080";

    @Autowired DataSource dataSource;
    @Autowired SmsActivationMapper activations;
    @Autowired SmsRegistrationMapper registrations;

    private final GoRegistrationClient go = mock(GoRegistrationClient.class);
    private final GrizzlySmsClient grizzly = mock(GrizzlySmsClient.class);
    private final IpProxyService proxies = mock(IpProxyService.class);
    private final CountryService countries = mock(CountryService.class);
    private final GoRegistrationProperties goProperties = new GoRegistrationProperties();
    private final AtomicLong proxySequence = new AtomicLong(100);
    private SmsRegistrationServiceImpl registrationService;
    private SmsActivationServiceImpl activationService;
    private SmsRegistrationWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String migration : new String[] {"V221__sms_activation.sql", "V223__sms_registration.sql"}) {
                statement.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
            }
        }
        SmsRegistrationMigration.apply(dataSource);
        var tenant = new GoRegistrationProperties.Tenant();
        tenant.setProfileId(42L);
        tenant.setBusinessProfileId(43L);
        goProperties.setEnabled(true);
        goProperties.setConcurrency(8);
        goProperties.getTenants().put(TENANT, tenant);
        var grizzlyProperties = new GrizzlySmsProperties();
        grizzlyProperties.setEnabled(true);
        grizzlyProperties.setPurchasesEnabled(true);
        var converter = Mappers.getMapper(SmsActivationConverter.class);
        registrationService = new SmsRegistrationServiceImpl(registrations, activations, converter, proxies, countries,
                goProperties);
        activationService = new SmsActivationServiceImpl(activations, converter, mock(AccountRegistrationService.class),
                grizzly, grizzlyProperties, registrationService);
        worker = new SmsRegistrationWorker(registrations, activations, activationService, go, proxies,
                new ProxyResolver(), goProperties);
        var endpoint = new ProxyEndpoint(ProxyEndpoint.PROTOCOL_SOCKS5, "gw.example", 1080,
                new ProxyCredentials("user", "pw_session-abc"), "美国");
        when(countries.resolveIpRegionByPhonePrefix(anyString())).thenReturn("美国");
        when(proxies.allocateRegistrationEndpoint(anyLong(), anyString())).thenAnswer(invocation ->
                new IpProxyAllocation(proxySequence.incrementAndGet(), endpoint, "src"));
        when(proxies.findEndpoint(anyLong())).thenReturn(endpoint);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
        TenantContext.clear();
    }

    @Test
    void businessTypeAndProfileStayFrozenThroughReplayAndVerification() {
        long id = order("biz-1", "12025550123", 60_000);
        var started = registrationService.start(id, 5L, 2);
        assertThat(started.registrationAccountType()).isEqualTo(2);
        assertThat(registration(id).getAccountType()).isEqualTo(2);
        assertThat(registration(id).getGoProfileId()).isEqualTo(43L);
        assertThat(registrationService.start(id, 5L, 2).registrationAccountType()).isEqualTo(2);
        assertThatThrownBy(() -> registrationService.start(id, 5L, 1)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能更改");
        // 改配置不能影响已开始事务；首次超时后重放必须仍然使用旧商业配置和类型。
        goProperties.getTenants().get(TENANT).setBusinessProfileId(99L);
        when(go.requestCode(eq(TENANT), anyString(), any(GoRegistrationRequest.class)))
                .thenReturn(GoRegistrationResponse.of(Kind.NO_RESPONSE), done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("biz-1")).thenReturn(waiting());
        worker.tick();
        worker.tick();
        String key = "sms-go-v1:7:" + registration(id).getId();
        verify(go, times(2)).requestCode(TENANT, key + ":send",
                new GoRegistrationRequest("+12025550123", 43L, PROXY_URL, true));
        when(grizzly.getStatus("biz-1")).thenReturn(received("004271"));
        when(go.verifyCode(eq(TENANT), anyString(), eq(TX), eq("004271"), eq(3L)))
                .thenReturn(done(GoRegistrationState.REGISTERED, 5, true));
        worker.tick();
        assertThat(registration(id).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(registration(id).getAccountType()).isEqualTo(2);
        assertThat(registration(id).getGoProfileId()).isEqualTo(43L);
    }

    @Test
    void unsupportedOrUnconfiguredTypeCannotAllocateProxy() {
        long id = order("biz-2", "12025550124", 60_000);
        assertThat(registrationService.availableAccountTypes()).containsExactly(1, 2);
        goProperties.getTenants().get(TENANT).setBusinessProfileId(0);
        assertThat(registrationService.availableAccountTypes()).containsExactly(1);
        assertThatThrownBy(() -> registrationService.start(id, 5L, 2)).hasMessageContaining("未配置");
        assertThatThrownBy(() -> registrationService.start(id, 5L, 3)).hasMessageContaining("账号类型");
        verify(proxies, never()).allocateRegistrationEndpoint(anyLong(), anyString());
        assertThat(registration(id)).isNull();
        goProperties.getTenants().get(TENANT).setProfileId(0);
        goProperties.getTenants().get(TENANT).setBusinessProfileId(43);
        assertThat(registrationService.availableAccountTypes()).containsExactly(2);
    }

    @Test
    void singleNumberGoesThroughSendWaitVerifyAndCompletesOrderOnce() {
        long orderId = order("9001", "12025550123", 60_000);
        when(go.requestCode(eq(TENANT), anyString(), eq(new GoRegistrationRequest("+12025550123", 42L, PROXY_URL, false)))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("9001")).thenReturn(waiting());

        var started = registrationService.start(orderId, 5L, 1);
        Long proxyId = registration(orderId).getProxyId();
        assertThat(started.registrationState()).isEqualTo("REQUESTING");
        worker.tick();
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.WAITING_CODE.code());
        assertThat(registration(orderId).getGoTransactionId()).isEqualTo(TX);
        assertThat(registration(orderId).getGoVersion()).isEqualTo(3L);
        String key = "sms-go-v1:7:" + registration(orderId).getId();
        verify(go).requestCode(TENANT, key + ":send", new GoRegistrationRequest("+12025550123", 42L, PROXY_URL, false));

        worker.tick();
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.WAITING_CODE.code());
        assertThat(registration(orderId).getProxyId()).isEqualTo(proxyId);
        verify(proxies, never()).releaseRegistrationAllocation(anyLong(), anyLong());
        when(grizzly.getStatus("9001")).thenReturn(received("004271"));
        when(go.verifyCode(eq(TENANT), anyString(), eq(TX), eq("004271"), eq(3L))).thenReturn(done(GoRegistrationState.REGISTERED, 5, true));
        worker.tick();

        verify(go).verifyCode(TENANT, key + ":verify", TX, "004271", 3L);
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(order(orderId).getState()).isEqualTo(SmsActivationState.COMPLETED.code());
        verify(grizzly, times(1)).setStatus("9001", GrizzlyStatusUpdate.COMPLETE);
        assertThat(registration(orderId).getProxyId()).isNull();
        verify(proxies).releaseRegistrationAllocation(orderId, proxyId);
        worker.tick();
        verify(go, times(1)).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(proxies, times(1)).releaseRegistrationAllocation(orderId, proxyId);
    }

    @Test
    void severalNumbersOutOfOrderKeepTheirOwnTransactionsAndCodes() {
        String[] phones = {"12025550101", "12025550102", "12025550103"};
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = order("900" + i, phones[i], 60_000);
            int index = i;
            when(go.requestCode(eq(TENANT), anyString(), eq(new GoRegistrationRequest("+" + phones[i], 42L, PROXY_URL, false))))
                    .thenReturn(snapshot(GoRegistrationState.WAITING_CODE, "tx-" + index, 10 + index, false));
            when(grizzly.getStatus("900" + i)).thenReturn(waiting());
            registrationService.start(ids[i], 5L, 1);
        }
        worker.tick();
        for (int i = 0; i < 3; i++) {
            assertThat(registration(ids[i]).getGoTransactionId()).isEqualTo("tx-" + i);
        }

        // 到码顺序 C、A、B；每个验证码只能交给自己的事务和版本。
        String[] codes = {"111111", "222222", "333333"};
        int[] arrival = {2, 0, 1};
        for (int step = 0; step < 3; step++) {
            int index = arrival[step];
            when(grizzly.getStatus("900" + index)).thenReturn(received(codes[index]));
            when(go.verifyCode(eq(TENANT), anyString(), eq("tx-" + index), eq(codes[index]), eq(10L + index)))
                    .thenReturn(done(GoRegistrationState.REGISTERED, 20 + index, true));
            worker.tick();
            verify(go).verifyCode(eq(TENANT), anyString(), eq("tx-" + index), eq(codes[index]), eq(10L + index));
            assertThat(registration(ids[index]).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
            assertThat(registration(ids[index]).getProxyId()).isNull();
            verify(proxies).releaseRegistrationAllocation(eq(ids[index]), anyLong());
        }
        verify(go, times(3)).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void doubleClickKeepsOneRegistrationAndOneProxy() {
        long orderId = order("9001", "12025550123", 60_000);

        var first = registrationService.start(orderId, 5L, 1);
        var second = registrationService.start(orderId, 5L, 1);

        assertThat(second.registrationState()).isEqualTo(first.registrationState());
        verify(proxies, times(1)).allocateRegistrationEndpoint(anyLong(), anyString());
        assertThat(registrations.countActiveByActivation(orderId)).isEqualTo(1);
    }

    @Test
    void sendAndVerifyAreReplayedWithIdenticalArgumentsUntilGoAnswers() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class)))
                .thenReturn(GoRegistrationResponse.of(Kind.NO_RESPONSE), GoRegistrationResponse.of(Kind.IN_PROGRESS),
                        GoRegistrationResponse.of(Kind.RATE_LIMITED), done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("9001")).thenReturn(received("004271"));
        when(go.verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(GoRegistrationResponse.of(Kind.NO_RESPONSE), done(GoRegistrationState.REGISTERED, 5, true));

        for (int i = 0; i < 4; i++) {
            worker.tick();
        }
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.WAITING_CODE.code());
        // 四次发码请求逐项相同：同键、同号码、同配置、同代理连接串。
        String key = "sms-go-v1:7:" + registration(orderId).getId() + ":send";
        verify(go, times(4)).requestCode(TENANT, key, new GoRegistrationRequest("+12025550123", 42L, PROXY_URL, false));

        worker.tick();
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.VERIFYING.code());
        assertThat(registration(orderId).getProxyId()).isNotNull();
        verify(proxies, never()).releaseRegistrationAllocation(anyLong(), anyLong());
        worker.tick();
        String verifyKey = "sms-go-v1:7:" + registration(orderId).getId() + ":verify";
        verify(go, times(2)).verifyCode(TENANT, verifyKey, TX, "004271", 3L);
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(registration(orderId).getProxyId()).isNull();
        verify(proxies).releaseRegistrationAllocation(eq(orderId), anyLong());
    }

    @ParameterizedTest
    @CsvSource({
            "CONFLICT, '', GO_CONFLICT",
            "UNAVAILABLE, '', GO_UNAVAILABLE",
            "OUTCOME_UNKNOWN, '', GO_OUTCOME_UNKNOWN",
            "REJECTED, '', GO_REJECTED",
            "REJECTED, ACTION_REQUIRED, GO_ACTION_REQUIRED"})
    void goRefusalOnSendStopsReleasesProxyAndGivesTheOrderBackToTheOperator(Kind kind, String errorCode,
            SmsRegistrationFailure expected) {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class)))
                .thenReturn(new GoRegistrationResponse(kind, new GoRegistrationSnapshot("", GoRegistrationState.UNRECOGNIZED, 0, false, errorCode == null ? "" : errorCode)));

        worker.tick();

        var row = registration(orderId);
        assertThat(row.getState()).isEqualTo(SmsRegistrationState.ACTION_REQUIRED.code());
        assertThat(row.getFailureCode()).isEqualTo(expected.name());
        assertThat(row.getProxyId()).isNull();
        verify(proxies).releaseRegistrationAllocation(eq(orderId), anyLong());
        // 办理结束后订单不再被占用，旧的取消按钮可用；失败后不会再向 Go 发请求。
        assertThat(registrationService.isOccupied(orderId)).isFalse();
        assertThat(activationService.cancel(orderId).state()).isEqualTo("CANCELLED");
        worker.tick();
        verify(go, times(1)).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
        verify(proxies, times(1)).releaseRegistrationAllocation(eq(orderId), anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = SmsRegistrationState.class, names = {"REGISTERED", "ACTION_REQUIRED"})
    void terminalProxyReleaseIsRetriedAfterRestartWithoutRepeatingRegistration(SmsRegistrationState state) {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        var row = registration(orderId);
        Long proxyId = row.getProxyId();
        // 模拟终态已提交，但进程在释放代理之前退出；不需要新增恢复状态。
        row.setState(state.code());
        assertThat(registrations.transition(row, SmsRegistrationState.REQUESTING.code())).isOne();
        doThrow(new IllegalStateException("temporary release failure")).doNothing()
                .when(proxies).releaseRegistrationAllocation(orderId, proxyId);

        worker.tick();

        assertThat(registration(orderId).getState()).isEqualTo(state.code());
        assertThat(registration(orderId).getProxyId()).isEqualTo(proxyId);
        verify(proxies).releaseRegistrationAllocation(orderId, proxyId);

        worker.shutdown();
        worker = new SmsRegistrationWorker(registrations, activations, activationService, go, proxies,
                new ProxyResolver(), goProperties);
        TenantContext.set(8L);
        worker.tick();
        assertThat(TenantContext.get()).isEqualTo(8L);
        TenantContext.set(TENANT);
        assertThat(registration(orderId).getState()).isEqualTo(state.code());
        assertThat(registration(orderId).getProxyId()).isNull();
        worker.tick();

        verify(proxies, times(2)).releaseRegistrationAllocation(orderId, proxyId);
        verify(go, never()).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
        verify(go, never()).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(grizzly, never()).setStatus(anyString(), any());
    }

    @Test
    void failedReleaseAfterSuccessDoesNotDelayOrderCompletionOrRepeatRegistration() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        Long proxyId = registration(orderId).getProxyId();
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class)))
                .thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("9001")).thenReturn(received("004271"));
        when(go.verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(done(GoRegistrationState.REGISTERED, 5, true));
        doThrow(new IllegalStateException("temporary release failure")).doNothing()
                .when(proxies).releaseRegistrationAllocation(orderId, proxyId);

        worker.tick();
        worker.tick();

        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(registration(orderId).getProxyId()).isEqualTo(proxyId);
        assertThat(order(orderId).getState()).isEqualTo(SmsActivationState.COMPLETED.code());
        verify(proxies, times(1)).releaseRegistrationAllocation(orderId, proxyId);

        worker.tick();
        worker.tick();

        assertThat(registration(orderId).getProxyId()).isNull();
        verify(proxies, times(2)).releaseRegistrationAllocation(orderId, proxyId);
        verify(go, times(1)).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
        verify(go, times(1)).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(grizzly, times(1)).setStatus("9001", GrizzlyStatusUpdate.COMPLETE);
    }

    @Test
    void releasedProxyWithFailedMarkerWriteIsCleanedUpOnNextTick() throws Exception {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        var row = registration(orderId);
        Long proxyId = row.getProxyId();
        row.setState(SmsRegistrationState.ACTION_REQUIRED.code());
        assertThat(registrations.transition(row, SmsRegistrationState.REQUESTING.code())).isOne();
        // 用真实数据库拒绝本轮清空标记，模拟代理已归还但登记释放结果失败。
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE sms_registration ADD CONSTRAINT fail_proxy_clear CHECK (proxy_id IS NOT NULL)");
        }

        worker.tick();

        verify(proxies).releaseRegistrationAllocation(orderId, proxyId);
        assertThat(registration(orderId).getProxyId()).isEqualTo(proxyId);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE sms_registration DROP CONSTRAINT fail_proxy_clear");
        }
        worker.tick();
        worker.tick();

        assertThat(registration(orderId).getProxyId()).isNull();
        verify(proxies, times(2)).releaseRegistrationAllocation(orderId, proxyId);
        verify(go, never()).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
        verify(go, never()).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(grizzly, never()).setStatus(anyString(), any());
    }

    @Test
    void occupiedOrderBlocksManualButtonsUntilRegistrationEnds() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);

        assertThatThrownBy(() -> activationService.cancel(orderId)).hasMessageContaining("注册办理中");
        assertThat(activationService.refresh(orderId).registrationState()).isEqualTo("REQUESTING");
        verify(grizzly, never()).getStatus(anyString());
        verify(grizzly, never()).setStatus(anyString(), any());
    }

    @Test
    void cancelledOrExpiredOrderIsNeverSentToGo() {
        long cancelled = order("9001", "12025550101", 60_000);
        long expired = order("9002", "12025550102", 60_000);
        registrationService.start(cancelled, 5L, 1);
        registrationService.start(expired, 5L, 1);
        setOrder(cancelled, SmsActivationState.CANCELLED, null, 60_000);
        setOrder(expired, SmsActivationState.WAITING_CODE, null, -1);

        worker.tick();

        assertThat(registration(cancelled).getFailureCode()).isEqualTo(SmsRegistrationFailure.ORDER_NOT_WAITING.name());
        assertThat(registration(expired).getFailureCode()).isEqualTo(SmsRegistrationFailure.ORDER_EXPIRED.name());
        verify(go, never()).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
        verify(proxies, times(2)).releaseRegistrationAllocation(anyLong(), anyLong());
    }

    @Test
    void orderExpiringWhileWaitingForCodeStopsWithoutCallingGoAgain() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        worker.tick();
        setOrder(orderId, SmsActivationState.WAITING_CODE, null, -1);

        worker.tick();

        assertThat(registration(orderId).getFailureCode()).isEqualTo(SmsRegistrationFailure.ORDER_EXPIRED.name());
        assertThat(order(orderId).getState()).isEqualTo(SmsActivationState.EXPIRED.code());
        assertThat(registration(orderId).getProxyId()).isNull();
        verify(go, never()).verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(proxies).releaseRegistrationAllocation(eq(orderId), anyLong());
    }

    @Test
    void registeredStaysRegisteredEvenWhenProviderCompleteFailsAndIsNotRetried() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("9001")).thenReturn(received("004271"));
        when(go.verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(done(GoRegistrationState.REGISTERED, 5, true));
        doThrow(new GrizzlySmsException(GrizzlySmsFailure.TRANSPORT_ERROR, true)).when(grizzly).setStatus("9001", GrizzlyStatusUpdate.COMPLETE);

        worker.tick();
        worker.tick();
        worker.tick();

        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(order(orderId).getState()).isEqualTo(SmsActivationState.RECEIVED.code());
        assertThat(registration(orderId).getProxyId()).isNull();
        verify(proxies).releaseRegistrationAllocation(eq(orderId), anyLong());
        verify(grizzly, times(1)).setStatus("9001", GrizzlyStatusUpdate.COMPLETE);
        // 办理已结束，订单不再被占用，运营用旧的“完成”按钮收尾。
        org.mockito.Mockito.reset(grizzly);
        assertThat(activationService.complete(orderId).state()).isEqualTo("COMPLETED");
    }

    @Test
    void verifyResultThatIsNotAnAvailableRegistrationIsNeverTreatedAsSuccess() {
        long notAvailable = order("9001", "12025550101", 60_000);
        long unknown = order("9002", "12025550102", 60_000);
        for (long id : new long[] {notAvailable, unknown}) {
            registrationService.start(id, 5L, 1);
        }
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus(anyString())).thenReturn(received("004271"));
        when(go.verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(done(GoRegistrationState.REGISTERED, 5, false), GoRegistrationResponse.of(Kind.OUTCOME_UNKNOWN));

        worker.tick();
        worker.tick();

        assertThat(registrations.countActiveByActivation(notAvailable) + registrations.countActiveByActivation(unknown)).isZero();
        assertThat(registration(notAvailable).getFailureCode()).isEqualTo(SmsRegistrationFailure.GO_OUTCOME_UNKNOWN.name());
        assertThat(registration(unknown).getFailureCode()).isEqualTo(SmsRegistrationFailure.GO_OUTCOME_UNKNOWN.name());
        verify(grizzly, never()).setStatus(anyString(), eq(GrizzlyStatusUpdate.COMPLETE));
    }

    @Test
    void noAnswerUntilOrderExpiresChecksGoOnceAndAcceptsOnlyAvailableRegistration() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        when(grizzly.getStatus("9001")).thenReturn(received("004271"));
        when(go.verifyCode(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(GoRegistrationResponse.of(Kind.NO_RESPONSE));
        worker.tick();
        worker.tick();
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.VERIFYING.code());
        setOrder(orderId, SmsActivationState.RECEIVED, "004271", -1);
        when(go.status(TENANT, TX)).thenReturn(done(GoRegistrationState.REGISTERED, 5, true));

        worker.tick();

        verify(go, times(1)).status(TENANT, TX);
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
    }

    @Test
    void unreadableProxyStopsBeforeAnyGoCall() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(proxies.findEndpoint(anyLong())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "代理不存在"));

        worker.tick();

        assertThat(registration(orderId).getFailureCode()).isEqualTo(SmsRegistrationFailure.PROXY_UNAVAILABLE.name());
        verify(go, never()).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
    }

    @Test
    void startIsRefusedWithoutSideEffectsWhenNotEligible() {
        long withCode = order("9001", "12025550101", 60_000);
        setOrder(withCode, SmsActivationState.WAITING_CODE, "123456", 60_000);
        long expired = order("9002", "12025550102", -1);
        long received = order("9003", "12025550103", 60_000);
        setOrder(received, SmsActivationState.RECEIVED, "123456", 60_000);
        for (long id : new long[] {withCode, expired, received}) {
            assertThatThrownBy(() -> registrationService.start(id, 5L, 1)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> registrationService.start(999L, 5L, 1)).isInstanceOf(BusinessException.class);

        goProperties.getTenants().clear();
        long fine = order("9004", "12025550104", 60_000);
        assertThatThrownBy(() -> registrationService.start(fine, 5L, 1)).hasMessageContaining("GO_REGISTRATION_DISABLED");
        assertThat(registrationService.disabledReason()).isEqualTo("GO_REGISTRATION_DISABLED");

        verify(proxies, never()).allocateRegistrationEndpoint(anyLong(), anyString());
        assertThat(registrations.findByActivation(fine)).isNull();
    }

    @Test
    void noIdleProxyCreatesNoRegistrationAndNeverCallsGo() {
        long orderId = order("9001", "12025550123", 60_000);
        when(proxies.allocateRegistrationEndpoint(anyLong(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION, "暂无空闲代理"));

        assertThatThrownBy(() -> registrationService.start(orderId, 5L, 1)).hasMessageContaining("暂无空闲代理");

        assertThat(registrations.findByActivation(orderId)).isNull();
        worker.tick();
        verify(go, never()).requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class));
    }

    @Test
    void workerRestoresEachRecordsTenantAndOtherTenantsCannotSeeTheOccupation() {
        long orderId = order("9001", "12025550123", 60_000);
        registrationService.start(orderId, 5L, 1);
        when(go.requestCode(anyLong(), anyString(), any(GoRegistrationRequest.class))).thenReturn(done(GoRegistrationState.WAITING_CODE, 3, false));
        TenantContext.set(8L);

        worker.tick();

        // worker 在线程池里按记录租户 7 工作，调用方线程的租户上下文不受影响。
        assertThat(TenantContext.get()).isEqualTo(8L);
        verify(go).requestCode(eq(TENANT), anyString(), any(GoRegistrationRequest.class));
        assertThat(registrationService.isOccupied(orderId)).isFalse();
        TenantContext.set(TENANT);
        assertThat(registration(orderId).getState()).isEqualTo(SmsRegistrationState.WAITING_CODE.code());
    }

    private long order(String activationId, String phone, long expiresInMillis) {
        var row = new SmsActivation();
        row.setActivationId(activationId);
        row.setPhoneNumber(phone);
        row.setServiceCode("wa");
        row.setCountryId("187");
        row.setCost(new BigDecimal("0.5"));
        row.setCurrency(840);
        row.setState(SmsActivationState.WAITING_CODE.code());
        row.setCancelAfter(0L);
        row.setExpiresAt(System.currentTimeMillis() + expiresInMillis);
        row.setCreatedBy(5L);
        row.setCreatedAt(1L);
        row.setUpdatedAt(1L);
        activations.insert(row);
        return row.getId();
    }

    private void setOrder(long id, SmsActivationState state, String code, long expiresInMillis) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE sms_activation SET state=" + state.code() + ", sms_code="
                    + (code == null ? "NULL" : "'" + code + "'") + ", expires_at=" + (System.currentTimeMillis() + expiresInMillis)
                    + " WHERE id=" + id);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SmsActivation order(long id) { return activations.find(id); }

    private SmsRegistration registration(long orderId) { return registrations.findByActivation(orderId); }

    private static GoRegistrationResponse done(GoRegistrationState state, long version, boolean resultAvailable) {
        return snapshot(state, TX, version, resultAvailable);
    }

    private static GoRegistrationResponse snapshot(GoRegistrationState state, String transactionId, long version,
            boolean resultAvailable) {
        return new GoRegistrationResponse(Kind.DONE, new GoRegistrationSnapshot(transactionId, state, version, resultAvailable, ""));
    }

    private static GrizzlySmsStatus waiting() {
        return new GrizzlySmsStatus(GrizzlySmsStatus.State.WAITING_CODE, Optional.empty(), Optional.empty());
    }

    private static GrizzlySmsStatus received(String code) {
        return new GrizzlySmsStatus(GrizzlySmsStatus.State.RECEIVED, Optional.of(code), Optional.empty());
    }

    @Configuration @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:sms_registration_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/SmsActivationMapper.xml"),
                    new ClassPathResource("mapper/account/SmsRegistrationMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SmsActivationMapper activationMapper(SqlSessionTemplate template) { return template.getMapper(SmsActivationMapper.class); }
        @Bean SmsRegistrationMapper registrationMapper(SqlSessionTemplate template) { return template.getMapper(SmsRegistrationMapper.class); }
    }
}
