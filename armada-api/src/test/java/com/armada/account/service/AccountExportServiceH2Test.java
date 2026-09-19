package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.armada.account.mapper.AccountExportMapper;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.dto.AccountExportCreateDTO;
import com.armada.account.model.entity.AccountState;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实 Mapper、租户插件和 Spring 事务验证导出交付，所有凭据均为合成材料。 */
@SpringJUnitConfig(AccountExportServiceH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class AccountExportServiceH2Test {
    @Autowired DataSource dataSource;
    @Autowired AccountExportService service;
    @Autowired AccountExportMapper mapper;
    @Autowired AccountStateMapper states;
    @Autowired AccountCredentialMapper credentials;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired IpProxyService proxies;
    JdbcTemplate jdbc;
    final AuthPrincipal user = new AuthPrincipal(7, 1, "test", "test", "test", "test",
            List.of("TENANT_ADMIN"), List.of("tenant:account:view", "tenant:account:edit"));

    @BeforeEach
    void setup() {
        reset(proxies);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE group_pull_marketing_task(id BIGINT, tenant_id BIGINT, builder_group_id BIGINT, resource_status INT)");
        jdbc.execute("CREATE TABLE account_group(id BIGINT PRIMARY KEY, tenant_id BIGINT, marketing_occupancy_task_id BIGINT)");
        jdbc.execute("CREATE TABLE account(id BIGINT PRIMARY KEY, tenant_id BIGINT, account_group_id BIGINT, owner_user_id BIGINT, dispatched_at BIGINT, deleted_at BIGINT, updated_at BIGINT)");
        jdbc.execute("CREATE TABLE account_state(id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, account_id BIGINT, account_state INT, login_state INT, desired_login_state INT, state_source VARCHAR(40), updated_at BIGINT, last_state_sync_time BIGINT)");
        jdbc.execute("CREATE TABLE account_credential(id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, account_id BIGINT, deleted_at BIGINT, updated_at BIGINT)");
        jdbc.execute("CREATE TABLE account_import_batch(id BIGINT PRIMARY KEY, tenant_id BIGINT, import_format INT, device_os INT)");
        jdbc.execute("CREATE TABLE account_import_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, account_id BIGINT, batch_id BIGINT, parse_result INT, raw_payload TEXT)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V204__account_selected_export.sql")).execute(dataSource);
        jdbc.update("INSERT INTO account_group VALUES(1,1,NULL),(2,2,NULL)");
        seed(1, 10, 1, "111,a,b,c,d,e");
        seed(1, 11, 3, "{\"full\":true}");
        seed(1, 12, 2, "{\"json\":true}");
        seed(2, 20, 2, "{\"otherTenant\":true}");
        TenantContext.set(1L);
    }

    private void seed(long tenant, long id, int format, String raw) {
        jdbc.update("INSERT INTO account VALUES(?,?,?,?,NULL,NULL,0)", id, tenant, tenant, 7);
        jdbc.update("INSERT INTO account_state(tenant_id,account_id,account_state,login_state,desired_login_state,state_source,updated_at,last_state_sync_time) VALUES(?,?,2,2,2,'STATE_CHANGED',0,0)", tenant, id);
        jdbc.update("INSERT INTO account_credential(tenant_id,account_id) VALUES(?,?)", tenant, id);
        jdbc.update("INSERT INTO account_import_batch VALUES(?,?,?,1)", id, tenant, format);
        jdbc.update("INSERT INTO account_import_detail(tenant_id,account_id,batch_id,parse_result,raw_payload) VALUES(?,?,?,1,?)", tenant, id, id, raw);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void selectedOnlyAndIdempotentDeliveryDoNotDeleteOnDownload() {
        var request = request(12L, 10L, 10L);
        var job = service.create(request, user);
        assertThat(job.accountCount()).isEqualTo(2);
        assertThat(service.create(request, user)).isEqualTo(job);
        assertThat(service.download(job.id(), user).getArchive()).isNotEmpty();
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(state(11)).isEqualTo(2);
        assertThat(state(20)).isEqualTo(2);
        assertThatThrownBy(() -> service.complete(job.id(), "wrong", user)).isInstanceOf(BusinessException.class);
        service.complete(job.id(), job.sha256(), user);
        service.complete(job.id(), job.sha256(), user);
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isEqualTo(2);
        assertThat(count("account_credential WHERE deleted_at IS NOT NULL")).isEqualTo(2);
        assertThat(service.download(job.id(), user).getArchive()).isNotEmpty();
    }

    @Test
    void everyNonOfflineStateFailsWholeSelectionWithoutMutation() {
        for (Integer login : new Integer[] {1, 3, null}) {
            jdbc.update("UPDATE account_state SET login_state=? WHERE account_id=11", login);
            assertThatThrownBy(() -> service.create(request(10L, 11L), user))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("非离线");
            assertThat(state(10)).isEqualTo(2);
            assertThat(count("account_export_job")).isZero();
        }
    }

    @Test
    void crossTenantMissingAndOccupiedAccountsFailClosed() {
        for (var ids : List.of(List.of(10L, 20L), List.of(10L, 999L), List.<Long>of())) {
            assertThatThrownBy(() -> service.create(new AccountExportCreateDTO(UUID.randomUUID().toString(), ids), user))
                    .isInstanceOf(BusinessException.class);
        }
        jdbc.update("UPDATE account_group SET marketing_occupancy_task_id=99 WHERE id=1");
        assertThatThrownBy(() -> service.create(request(10L), user)).isInstanceOf(BusinessException.class);
        assertThat(count("account_export_job")).isZero();
    }

    @Test
    void absentAndAmbiguousOriginalsDoNotExportRuntimeFallback() {
        jdbc.update("UPDATE account_import_detail SET raw_payload=NULL WHERE account_id=10");
        assertThatThrownBy(() -> service.create(request(10L), user)).isInstanceOf(BusinessException.class);
        jdbc.update("INSERT INTO account_import_detail(tenant_id,account_id,batch_id,parse_result,raw_payload) VALUES(1,11,11,1,'{}')");
        assertThatThrownBy(() -> service.create(request(11L), user)).isInstanceOf(BusinessException.class);
        assertThat(count("account_export_job")).isZero();
    }

    @Test
    void removalFailureRollsBackWholeBatchAndLeavesArtifactRetryable() {
        var job = service.create(request(10L, 11L), user);
        doThrow(new IllegalStateException("synthetic failure")).when(proxies).releaseByAccount(11L);
        assertThatThrownBy(() -> service.complete(job.id(), job.sha256(), user)).isInstanceOf(IllegalStateException.class);
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(count("account_credential WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(service.list(user).get(0).status()).isEqualTo("READY");
        assertThat(service.download(job.id(), user).getArchive()).isNotEmpty();
    }

    @Test
    void cancelRestoresOriginalStateAndAllowsAnotherExport() {
        var job = service.create(request(10L), user);
        assertThat(state(10)).isEqualTo(4);
        assertThat(credentials.selectByAccountId(10L)).isNull();
        assertThat(credentials.selectByTenantAndAccountIds(1L, List.of(10L))).isEmpty();
        assertThat(states.claimPendingOnline(List.of(10L), System.currentTimeMillis())).isZero();
        assertThat(states.updateDesiredLoginState(List.of(10L), 1, System.currentTimeMillis())).isZero();
        assertThat(states.updateDesiredLoginState(List.of(10L), 2, System.currentTimeMillis())).isEqualTo(1);
        service.cancel(job.id(), user);
        service.cancel(job.id(), user);
        assertThat(state(10)).isEqualTo(2);
        assertThat(credentials.selectByAccountId(10L)).isNotNull();
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(service.create(request(10L), user).id()).isNotEqualTo(job.id());
    }

    @Test
    void aRealOnlineEventBlocksDeliveryButCannotEraseExportHold() {
        var job = service.create(request(10L), user);
        var event = new AccountState();
        event.setAccountId(10L); event.setLoginState(1); event.setStateSource("ONLINE");
        event.setLastStateSyncTime(System.currentTimeMillis()); event.setUpdatedAt(System.currentTimeMillis());
        states.updateLoginState(event);
        assertThatThrownBy(() -> service.download(job.id(), user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.complete(job.id(), job.sha256(), user)).isInstanceOf(BusinessException.class);
        assertThat(state(10)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT state_source FROM account_state WHERE account_id=10", String.class)).isEqualTo("ACCOUNT_EXPORT");
    }

    @Test
    void exportRecordsAndFilesAreBoundToTenantAndCreator() {
        var job = service.create(request(10L), user);
        var other = new AuthPrincipal(8, 1, "x", "x", "x", "x", List.of("TENANT_ADMIN"), List.of());
        assertThat(service.list(other)).isEmpty();
        assertThatThrownBy(() -> service.download(job.id(), other)).isInstanceOf(BusinessException.class);
        TenantContext.set(2L);
        var foreign = new AuthPrincipal(7, 2, "x", "x", "x", "x", List.of("TENANT_ADMIN"), List.of());
        assertThatThrownBy(() -> service.download(job.id(), foreign)).isInstanceOf(BusinessException.class);
    }

    @Test
    void expirationCancelsUndeliveredFileAndReleasesHold() {
        var job = service.create(request(10L), user);
        jdbc.update("UPDATE account_export_job SET expires_at=0 WHERE id=?", job.id());
        assertThatThrownBy(() -> service.download(job.id(), user)).isInstanceOf(BusinessException.class);
        assertThat(service.list(user).get(0).status()).isEqualTo("CANCELLED");
        assertThat(state(10)).isEqualTo(2);
        assertThat(count("account_export_job WHERE archive IS NOT NULL")).isZero();
    }

    @Test
    void backgroundExpiryNeedsNoBrowserAndKeepsCompletedAccountsDeleted() {
        var ready = service.create(request(10L), user);
        var done = service.create(request(11L), user);
        service.complete(done.id(), done.sha256(), user);
        jdbc.update("UPDATE account_export_job SET expires_at=0");
        service.expire(ready.id());
        service.expire(done.id());
        service.expire(done.id());
        assertThat(state(10)).isEqualTo(2);
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("account_export_job WHERE archive IS NOT NULL")).isZero();
    }

    @Test
    void onlineClaimWaitsForExportTransactionThenCannotReopenAccount() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        var export = CompletableFuture.runAsync(() -> {
            TenantContext.set(1L);
            try {
                tx.executeWithoutResult(status -> {
                    var rows = mapper.candidates(List.of(10L));
                    mapper.reserve(rows, System.currentTimeMillis());
                    locked.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                });
            } finally { TenantContext.clear(); }
        });
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var online = CompletableFuture.supplyAsync(() -> {
            TenantContext.set(1L);
            try { return states.claimPendingOnline(List.of(10L), System.currentTimeMillis()); }
            finally { TenantContext.clear(); }
        });
        try { assertThatThrownBy(() -> online.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class); }
        finally { release.countDown(); }
        export.get(5, TimeUnit.SECONDS);
        assertThat(online.get(5, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void finalConditionalUpdateRejectsChangesAfterUnlockedSnapshot() {
        var rows = mapper.candidates(List.of(10L));
        jdbc.update("UPDATE account_state SET login_state=1 WHERE account_id=10");
        assertThat(mapper.reserve(rows, System.currentTimeMillis())).isZero();
        jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=10");
        jdbc.update("UPDATE account SET dispatched_at=123 WHERE id=10");
        assertThat(mapper.reserve(rows, System.currentTimeMillis())).isZero();
        jdbc.update("UPDATE account SET dispatched_at=NULL, owner_user_id=99 WHERE id=10");
        assertThat(mapper.reserve(rows, System.currentTimeMillis())).isZero();
        jdbc.update("UPDATE account SET owner_user_id=7 WHERE id=10");
        jdbc.update("UPDATE account_group SET marketing_occupancy_task_id=99 WHERE id=1");
        assertThat(mapper.reserve(rows, System.currentTimeMillis())).isZero();
        jdbc.update("UPDATE account_group SET marketing_occupancy_task_id=NULL WHERE id=1");
        jdbc.update("INSERT INTO group_pull_marketing_task VALUES(99,1,1,2)");
        assertThat(mapper.reserve(rows, System.currentTimeMillis())).isZero();
        assertThat(state(10)).isEqualTo(2);
    }

    @Test
    void removalConditionRechecksOfflineAndTaskOccupancy() {
        var job = service.create(request(10L), user);
        var rows = mapper.candidates(List.of(10L));
        jdbc.update("UPDATE account_state SET login_state=1 WHERE account_id=10");
        assertThat(mapper.removeAccounts(rows, System.currentTimeMillis())).isZero();
        jdbc.update("UPDATE account_state SET login_state=2 WHERE account_id=10");
        jdbc.update("UPDATE account SET dispatched_at=123 WHERE id=10");
        assertThat(mapper.removeAccounts(rows, System.currentTimeMillis())).isZero();
        assertThat(count("account WHERE deleted_at IS NOT NULL")).isZero();
        assertThat(service.list(user).get(0).id()).isEqualTo(job.id());
    }

    private AccountExportCreateDTO request(Long... ids) { return new AccountExportCreateDTO(UUID.randomUUID().toString(), List.of(ids)); }
    private int count(String clause) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + clause, Integer.class); }
    private int state(long id) { return jdbc.queryForObject("SELECT account_state FROM account_state WHERE account_id=?", Integer.class, id); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            var h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:account_export;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=6000");
            h2.setUser("sa"); return h2;
        }
        @Bean SqlSessionFactory factory(DataSource ds, MybatisPlusInterceptor plugin) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            factory.setConfiguration(config); factory.setPlugins(plugin);
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountExportMapper.xml"),
                    new ClassPathResource("mapper/account/AccountCredentialMapper.xml"),
                    new ClassPathResource("mapper/account/AccountStateMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean AccountExportMapper mapper(SqlSessionTemplate t) { return t.getMapper(AccountExportMapper.class); }
        @Bean AccountStateMapper states(SqlSessionTemplate t) { return t.getMapper(AccountStateMapper.class); }
        @Bean AccountCredentialMapper credentials(SqlSessionTemplate t) { return t.getMapper(AccountCredentialMapper.class); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean IpProxyService proxies() { return mock(IpProxyService.class); }
        @Bean AccountExportService service(AccountExportMapper m, IpProxyService p) {
            return new AccountExportService(m, mock(AccountGroupMapper.class), new AccountExportArchive(),
                    mock(ProtocolCommandOutboxService.class), p);
        }
    }
}
