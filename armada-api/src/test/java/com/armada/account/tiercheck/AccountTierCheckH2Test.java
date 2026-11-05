package com.armada.account.tiercheck;

import com.armada.account.converter.AccountTierCheckConverter;
import com.armada.account.mapper.AccountTierCheckMapper;
import com.armada.account.model.dto.AccountTierCheckClaim;
import com.armada.account.model.dto.AccountTierCheckQuery;
import com.armada.account.model.dto.AccountTierCheckRequestDTO;
import com.armada.account.model.dto.AccountTierCheckWork;
import com.armada.account.service.AccountTierCheckProperties;
import com.armada.account.service.AccountTierCheckTransactions;
import com.armada.account.service.impl.AccountTierCheckServiceImpl;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.port.AccountIntegrityPort;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实迁移/Mapper XML/租户插件/短事务；全部使用保留的合成号码，不连接外部服务。 */
class AccountTierCheckH2Test {
    private JdbcTemplate jdbc;
    private AccountTierCheckMapper mapper;
    private AccountTierCheckServiceImpl service;
    private AccountTierCheckTransactions runner;
    private TransactionTemplate tx;
    private AccountIntegrityPort port;
    private CurrentIdentityService identity;
    private AccountTierCheckProperties config;
    private final AuthPrincipal admin = principal(10, 7, true);
    private long clock = 100_000_000;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tier_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        var sql = new ClassPathResource("db/migration/V225__account_tier_check.sql").getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("probe_account_ids JSON NOT NULL");
        // H2 不支持索引 COMMENT；其余约束/列注释按原迁移执行。
        // H2 将 JDBC 字符串存为 JSON 字符串字面量，MySQL 则按 JSON 文档解析；仅替换该存储类型。
        try (var c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ByteArrayResource(sql.replace(" JSON NOT NULL", " VARCHAR(40000) NOT NULL").replaceAll("(?m)(KEY [^\\n]+\\)) COMMENT '[^']*'", "$1").getBytes(StandardCharsets.UTF_8)));
        }
        jdbc.execute("CREATE TABLE account_group(id BIGINT PRIMARY KEY,tenant_id BIGINT,name VARCHAR(100),deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,owner_user_id BIGINT,account_group_id BIGINT,ws_phone VARCHAR(32),protocol_account_id VARCHAR(64),protocol_id VARCHAR(16),account_type INT,deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_state(account_id BIGINT PRIMARY KEY,tenant_id BIGINT,login_state INT)");
        jdbc.execute("CREATE TABLE account_credential(account_id BIGINT PRIMARY KEY,tenant_id BIGINT,updated_at BIGINT,cred_format INT,deleted_at BIGINT)");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
        var mybatis = new MyBatisConfig(); factory.setPlugins(mybatis.mybatisPlusInterceptor(mybatis.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMapper.xml"), new ClassPathResource("mapper/account/AccountTierCheckMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AccountTierCheckMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var converter = Mappers.getMapper(AccountTierCheckConverter.class);
        port = mock(AccountIntegrityPort.class); when(port.supports(ProtocolBackend.WEB)).thenReturn(true);
        identity = mock(CurrentIdentityService.class); when(identity.load(anyLong(), anyLong())).thenReturn(Optional.of(admin));
        config = new AccountTierCheckProperties();
        runner = new AccountTierCheckTransactions(mapper, converter, config, identity);
        service = new AccountTierCheckServiceImpl(mapper, converter, port);
        TenantContext.set(7L);
        jdbc.update("INSERT INTO account_group VALUES(11,7,'target',NULL),(12,7,'probe',NULL),(13,8,'foreign',NULL)");
        add(1, 7, 11, "WEB", 2, 1); add(2, 7, 11, "ANDROID", 2, 1);
        add(3, 7, 12, "WEB", 2, 1); add(4, 7, 11, "WEB", 1, 1);
        add(5, 7, 11, "WEB", 2, 2); add(6, 8, 13, "WEB", 2, 1);
        add(7, 7, 12, "WEB", 2, 1);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    private void add(long id, long tenant, long group, String backend, int type, int state) {
        jdbc.update("INSERT INTO account VALUES(?,?,10,?,?,?, ?,?,NULL)", id, tenant, group, "000000000" + String.format("%02d", id), "fixture_" + id, backend, type);
        jdbc.update("INSERT INTO account_state VALUES(?,?,?)", id, tenant, state);
        jdbc.update("INSERT INTO account_credential VALUES(?,?,100,?,NULL)", id, tenant, backend.equals("WEB") ? 2 : 1);
    }
    private static AuthPrincipal principal(long user, long tenant, boolean admin) {
        return new AuthPrincipal(user, tenant, "fixture", "fixture", "test", "test", admin ? List.of("TENANT_ADMIN") : List.of("USER"), List.of("tenant:account:view", "tenant:account:edit"));
    }
    private AccountTierCheckRequestDTO request(int mode, List<Long> ids, List<Long> probes) {
        return new AccountTierCheckRequestDTO(List.of(), ids, true, mode, probes, "fixture batch", null, UUID.randomUUID().toString(), null);
    }
    private AccountTierCheckRequestDTO previewed(AccountTierCheckRequestDTO r) {
        String token = service.preview(r, admin).previewToken();
        return new AccountTierCheckRequestDTO(r.accountGroupIds(), r.accountIds(), r.includePersonal(), r.mode(), r.probeAccountIds(), r.name(), r.sourceTaskId(), r.requestId(), token);
    }
    private long create(int mode, List<Long> ids, List<Long> probes) {
        var req = previewed(request(mode, ids, probes));
        return tx.execute(s -> service.create(req, admin)).id();
    }
    private AccountTierCheckWork work(long task, long account) {
        var item = mapper.items(task, new AccountTierCheckQuery()).stream().filter(i -> i.getAccountId() == account).findFirst().orElseThrow();
        return new AccountTierCheckWork(7L, task, item.getId());
    }
    private AccountTierCheckClaim claim(long task, long account) { return tx.execute(s -> runner.claim(work(task, account), clock)).orElseThrow(); }
    private void apply(AccountTierCheckClaim claim, ProtocolAccountIntegrity result) { tx.executeWithoutResult(s -> runner.apply(claim, result, clock)); }
    private static ProtocolAccountIntegrity ok(String tier) {
        return new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.OK, "BUSINESS", tier, false, false, true, 1234L, "ZZ", null);
    }
    @Test void previewClassifiesExclusionsAndProbeCanQueryAndroid() {
        var req = new AccountTierCheckRequestDTO(List.of(11L), List.of(), false, 1, List.of(), null, null, null, null);
        var preview = service.preview(req, admin);
        assertThat(preview.eligible()).isEqualTo(1); assertThat(preview.excluded()).isEqualTo(3);
        assertThat(preview.excludedReasons()).extracting("reason").containsExactlyInAnyOrder("协议暂不支持", "未包含个人号", "发起账号离线");
        assertThat(service.preview(request(2, List.of(2L, 5L), List.of(3L)), admin).eligible()).isEqualTo(2);
        assertThatThrownBy(() -> service.preview(request(2, List.of(1L), List.of(2L)), admin)).hasMessageContaining("协议暂不支持");
        assertThatThrownBy(() -> service.preview(request(2, List.of(1L), List.of(6L)), admin)).hasMessageContaining("探针");
        // capabilities only; a query must never occur in preview/create.
        org.mockito.Mockito.verify(port, org.mockito.Mockito.never()).query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
    @Test void idempotencyRejectsChangedParametersAndStalePreview() {
        var req = previewed(request(1, List.of(1L), List.of()));
        var first = tx.execute(s -> service.create(req, admin));
        assertThat(tx.execute(s -> service.create(req, admin)).id()).isEqualTo(first.id());
        var changed = new AccountTierCheckRequestDTO(req.accountGroupIds(), List.of(3L), true, 1, List.of(), req.name(), null, req.requestId(), req.previewToken());
        assertThatThrownBy(() -> tx.execute(s -> service.create(changed, admin))).hasMessageContaining("同一请求");
        var stale = previewed(request(1, List.of(1L), List.of()));
        jdbc.update("UPDATE account_credential SET updated_at=101 WHERE account_id=1");
        assertThatThrownBy(() -> tx.execute(s -> service.create(stale, admin))).hasMessageContaining("重新预览");
    }
    @Test void rejectsEmptyOrAmbiguousScopeAndInvalidProbes() {
        assertThatThrownBy(() -> service.preview(request(1, List.of(), List.of()), admin)).hasMessageContaining("二选一");
        assertThatThrownBy(() -> service.preview(new AccountTierCheckRequestDTO(List.of(11L), List.of(1L), true, 1, List.of(), null, null, null, null), admin)).hasMessageContaining("二选一");
        assertThatThrownBy(() -> service.preview(request(2, List.of(1L), List.of(5L)), admin)).hasMessageContaining("在线");
        assertThat(service.preview(request(1, List.of(6L, 99L), List.of()), admin).excluded()).isEqualTo(2);
    }
    @Test void tenantAndCreatorIsolationApplyToTasksItemsAndHistory() {
        long task = create(1, List.of(1L), List.of());
        apply(claim(task, 1), ok("TIER_1"));
        assertThatThrownBy(() -> service.detail(task, principal(20, 7, false))).hasMessageContaining("无权访问");
        assertThat(service.list(new AccountTierCheckQuery(), principal(20, 7, false)).total()).isZero();
        TenantContext.set(8L);
        assertThat(mapper.task(task)).isNull(); assertThat(mapper.items(task, new AccountTierCheckQuery())).isEmpty();
        assertThat(mapper.previousSuccess(1L, 0L)).isNull(); assertThat(mapper.history(1L, null)).isEmpty();
        assertThatThrownBy(() -> service.history(1L, principal(10, 8, true))).hasMessageContaining("不存在");
        TenantContext.clear(); assertThat(mapper.task(task)).isNull();
        assertThat(mapper.scan(clock, 20)).isEmpty(); // 完成批次无待查项；后台只暴露主键。
    }
    @Test void sqlFiltersPaginationAndLatestSuccessIgnoreSourceBatchAndFailures() {
        long first = create(1, List.of(1L), List.of()); apply(claim(first, 1), ok("TIER_0")); clock += 4000;
        long second = create(1, List.of(1L), List.of()); apply(claim(second, 1), ok("TIER_2")); clock += 4000;
        long failure = create(1, List.of(1L), List.of()); apply(claim(failure, 1), ProtocolAccountIntegrity.failure("TIMEOUT")); clock += 4000;
        long recheck = tx.execute(s -> service.recheck(first, admin)).id();
        apply(claim(recheck, 1), ok("TIER_1"));
        var latest = mapper.item(work(recheck, 1).itemId());
        assertThat(latest.getPrevItemId()).isEqualTo(work(second, 1).itemId()); assertThat(latest.getTierChange()).isEqualTo(3);
        var q = new AccountTierCheckQuery(); q.setFilter("changed");
        assertThat(service.items(recheck, q, admin).total()).isEqualTo(1);
        q.setFilter("lost"); assertThat(service.items(failure, q, admin).total()).isEqualTo(1);
        q = new AccountTierCheckQuery(); q.setStatus(3); q.setKeyword("fixture"); q.setPageSize(1); q.setPage(2);
        assertThat(service.list(q, admin).total()).isEqualTo(3); assertThat(service.list(q, admin).list()).hasSize(1);
        assertThat(service.history(1L, admin).entries()).extracting("taskId").containsExactly(recheck, failure, second, first);
    }
    @Test void oneExecutorInFlightAcrossTasksAndEmptyTriggersPersistentCooldown() {
        long a = create(2, List.of(1L), List.of(3L)); long b = create(2, List.of(2L), List.of(3L));
        var claimed = claim(a, 1);
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(a, 1), clock))).isEmpty();
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(b, 2), clock))).isEmpty();
        apply(claimed, new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.EMPTY, null, "BAD_FIELD", null, null, null, null, null, "EMPTY_RESPONSE"));
        var item = mapper.item(claimed.work().itemId());
        assertThat(item.getStatus()).isEqualTo(4); assertThat(item.getTrustTier()).isNull();
        clock += config.getCooldownMs() - 1;
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(b, 2), clock))).isEmpty();
        clock++; assertThat(claim(b, 2).executor().armadaAccountId()).isEqualTo(3);
    }
    @Test void roundCapPersistsAcrossBatchesAndRestarts() {
        config.setRoundSize(2);
        long a = create(2, List.of(1L, 2L, 4L), List.of(3L));
        apply(claim(a, 1), ok("TIER_1")); clock += 4000;
        apply(claim(a, 2), ok("TIER_1")); clock += 4000;
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(a, 4), clock))).isEmpty();
        clock += config.getCooldownMs();
        assertThat(claim(a, 4).attemptNo()).isEqualTo(1);
        assertThat(mapper.item(work(a, 4).itemId()).getExecutorRoundCount()).isEqualTo(1);
    }
    @Test void notAllowedIsUnqueryableAndOnlyFailuresRetry() {
        long a = create(2, List.of(1L, 2L), List.of(3L, 7L));
        var c = claim(a, 1); apply(c, new ProtocolAccountIntegrity(ProtocolAccountIntegrity.Status.NOT_ALLOWED, null, null, null, null, null, null, null, "NOT_ALLOWED"));
        var d = claim(a, 2); apply(d, ProtocolAccountIntegrity.failure("TIMEOUT"));
        assertThat(mapper.item(c.work().itemId()).getStatus()).isEqualTo(5);
        assertThat(tx.<Integer>execute(s -> service.retry(a, admin))).isEqualTo(1);
        assertThat(mapper.item(c.work().itemId()).getStatus()).isEqualTo(5);
    }
    @Test void recoveryFencesOldResponsesAndOfflineProbeIsReassigned() {
        long a = create(2, List.of(1L), List.of(3L, 7L)); var old = claim(a, 1);
        jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=3");
        assertThat(mapper.recoverable(clock, 20)).contains(old.work());
        tx.executeWithoutResult(s -> runner.recover(old.work(), clock));
        var next = claim(a, 1); assertThat(next.executor().armadaAccountId()).isEqualTo(7);
        apply(old, ok("TIER_3")); assertThat(mapper.item(old.work().itemId()).getStatus()).isEqualTo(2);
        apply(next, ok("TIER_1")); assertThat(mapper.item(old.work().itemId()).getTrustTier()).isEqualTo("TIER_1");
        clock += 4000;
        long b = create(1, List.of(1L), List.of()); var timeout = claim(b, 1); clock += config.getResultTimeoutMs();
        tx.executeWithoutResult(s -> runner.recover(timeout.work(), clock));
        assertThat(mapper.item(timeout.work().itemId()).getStatus()).isEqualTo(1);
        assertThat(claim(b, 1).attemptNo()).isEqualTo(2);
    }
    @Test void missingOnlineProbeAndOfflineSelfBecomeRetryableFailures() {
        long a = create(2, List.of(2L), List.of(3L)); jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=3");
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(a, 2), clock))).isEmpty();
        assertThat(mapper.item(work(a, 2).itemId()).getReasonCode()).isEqualTo("NO_PROBE_ONLINE");
        long b = create(1, List.of(1L), List.of()); jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=1");
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(b, 1), clock))).isEmpty();
        assertThat(mapper.item(work(b, 1).itemId()).getReasonCode()).isEqualTo("EXECUTOR_OFFLINE");
    }
    @Test void credentialOrPermissionChangesPreventWritingResults() {
        long a = create(1, List.of(1L), List.of()); var c = claim(a, 1);
        jdbc.update("UPDATE account_credential SET updated_at=102 WHERE account_id=1"); apply(c, ok("TIER_3"));
        assertThat(mapper.item(c.work().itemId()).getTrustTier()).isNull();
        assertThat(mapper.item(c.work().itemId()).getReasonCode()).isEqualTo("ACCOUNT_IDENTITY_CHANGED");
        long b = create(1, List.of(7L), List.of()); var d = claim(b, 7);
        when(identity.load(anyLong(), anyLong())).thenReturn(Optional.empty()); apply(d, ok("TIER_3"));
        assertThat(mapper.item(d.work().itemId()).getReasonCode()).isEqualTo("ACCESS_REVOKED");
    }
    @Test void stopCancelsPendingAndRecheckKeepsFixedAccountsEvenAfterDeletion() {
        long a = create(1, List.of(1L, 3L), List.of()); var c = claim(a, 1);
        tx.executeWithoutResult(s -> service.stop(a, admin)); apply(c, ok("TIER_1"));
        assertThat(mapper.item(work(a, 3).itemId()).getStatus()).isEqualTo(6);
        assertThat(mapper.task(a).getStatus()).isEqualTo(2);
        jdbc.update("UPDATE account SET deleted_at=1 WHERE id=3");
        long b = tx.execute(s -> service.recheck(a, admin)).id();
        assertThat(mapper.accountIds(b)).containsExactly(1L, 3L);
        assertThat(tx.<Optional<AccountTierCheckClaim>>execute(s -> runner.claim(work(b, 3), clock))).isEmpty();
        assertThat(mapper.item(work(b, 3).itemId()).getReasonCode()).isEqualTo("ACCOUNT_ACCESS_CHANGED");
    }
    @Test void recheckCapturesCurrentCredentialVersionWithoutChangingOldSnapshot() {
        long first = create(1, List.of(1L), List.of()); apply(claim(first, 1), ok("TIER_1")); clock += 4000;
        jdbc.update("UPDATE account_credential SET updated_at=900 WHERE account_id=1");
        long next = tx.execute(s -> service.recheck(first, admin)).id();
        apply(claim(next, 1), ok("TIER_2"));
        assertThat(mapper.item(work(next, 1).itemId()).getStatus()).isEqualTo(3);
        assertThat(mapper.item(work(first, 1).itemId()).getTrustTier()).isEqualTo("TIER_1");
    }
    @Test void accountRowLockSerializesTwoIndependentTransactions() throws Exception {
        long a = create(2, List.of(1L), List.of(3L)); long b = create(2, List.of(2L), List.of(3L));
        var claimed = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                TenantContext.set(7L);
                try { return tx.execute(s -> {
                    var result = runner.claim(work(a, 1), clock); claimed.countDown();
                    try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException ex) { throw new AssertionError(ex); }
                    return result;
                }); } finally { TenantContext.clear(); }
            });
            assertThat(claimed.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                TenantContext.set(7L); entered.countDown();
                try { return tx.execute(s -> runner.claim(work(b, 2), clock)); } finally { TenantContext.clear(); }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); assertThat(first.get(3, TimeUnit.SECONDS)).isPresent(); assertThat(second.get(3, TimeUnit.SECONDS)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_tier_check_item WHERE status=2", Integer.class)).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
