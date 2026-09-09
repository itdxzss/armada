package com.armada.account.contact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import com.armada.account.contact.mapper.AccountStatusAudienceMapper;
import com.armada.account.contact.model.entity.AccountStatusAudience;
import com.armada.account.contact.service.CloudStatusAudienceCollector;
import com.armada.account.contact.service.CloudStatusAudienceService;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.result.CloudContactsPage;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 执行真实迁移、Mapper、租户插件与 Spring 事务；协议请求才使用替身。 */
class AccountStatusAudienceH2Test {
    private AccountStatusAudienceMapper mapper;
    private TransactionTemplate transaction;
    private CloudStatusAudienceService service;
    private ContactPort port;
    private final SelectedAccount account = new SelectedAccount(12L,"12025550101","ANDROID","owned-test");

    @BeforeEach void setup() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:status_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = db.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V181__account_status_audience.sql"));
        }
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(db); factory.setConfiguration(config);
        MyBatisConfig production = new MyBatisConfig();
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/account/AccountStatusAudienceMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AccountStatusAudienceMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        port = mock(ContactPort.class);
        service = new CloudStatusAudienceService(mapper, new CloudStatusAudienceCollector(port), new ObjectMapper());
        TenantContext.set(7L);
    }
    @AfterEach void cleanup() { service.shutdown(); TenantContext.clear(); }

    @Test void leaseAndGenerationPreventDuplicateAndLateSnapshotWrites() {
        AccountStatusAudience first = row("first", 100L, 200L);
        mapper.ensure(first);
        assertThat(mapper.claim(first,100L)).isEqualTo(1);
        var duplicate = row("duplicate",110L,210L);
        assertThat(mapper.claim(duplicate,100L)).isZero();
        var next = row("next",201L,400L);
        assertThat(mapper.claim(next,100L)).isEqualTo(1);
        first.setUpdatedAt(202L); first.setSyncStatus(2); first.setContactNum(1); first.setJidsJson("[\"10001@lid\"]");
        assertThat(mapper.finish(first)).isZero();
        next.setUpdatedAt(203L); next.setSyncStatus(2); next.setContactNum(2); next.setJidsJson("[\"10002@lid\",\"10003@lid\"]");
        next.setExpiresAt(1000L);
        assertThat(mapper.finish(next)).isEqualTo(1);
        assertThat(mapper.selectByAccountId(12L).getJidsJson()).contains("10002@lid");
        assertThat(mapper.selectSummaries(List.of(12L)).get(0).getJidsJson()).isNull();
        TenantContext.set(8L);
        assertThat(mapper.selectByAccountId(12L)).isNull();
        assertThat(mapper.selectSummaries(List.of(12L))).isEmpty();
        assertThat(mapper.finish(next)).isZero();
        TenantContext.clear();
        assertThat(mapper.selectByAccountId(12L)).isNull();
    }

    @Test void rollbackDoesNotLaunchCloudRequestsAndCommitPublishesOnlyCompleteSnapshot() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001@lid"),"v1","",false));
        transaction.execute(status -> { service.resolve(account,false); status.setRollbackOnly(); return null; });
        verifyNoInteractions(port);
        assertThat(mapper.selectByAccountId(12L)).isNull();
        transaction.execute(status -> {
            assertThat(service.resolve(account,false).view().status()).isEqualTo("SYNCING");
            verifyNoInteractions(port); return null;
        });
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            TenantContext.set(7L);
            assertThat(mapper.selectByAccountId(12L).getSyncStatus()).isEqualTo(2);
        });
        var resolved = transaction.execute(status -> service.resolve(account,false));
        assertThat(resolved.jids()).containsExactly("10001@lid");
        verify(port,times(1)).cloudPage(any());
    }

    @Test void partialFailureHasNoReadyJidsAndDoesNotRetryEachTaskRound() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001@lid"),"v1","c1",true))
                .thenThrow(new IllegalStateException("private protocol error should never reach page"));
        transaction.execute(status -> service.resolve(account,false));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            TenantContext.set(7L);
            assertThat(mapper.selectByAccountId(12L).getSyncStatus()).isEqualTo(3);
        });
        var result = transaction.execute(status -> service.resolve(account,false));
        assertThat(result.jids()).isEmpty();
        assertThat(result.view().failCode()).isEqualTo("CLOUD_FETCH_FAILED");
        assertThat(mapper.selectByAccountId(12L).getJidsJson()).isNull();
        verify(port,times(2)).cloudPage(any());
    }

    private AccountStatusAudience row(String token, long now, long lease) {
        var row = new AccountStatusAudience(); row.setTenantId(7L); row.setAccountId(12L);
        row.setRequestToken(token); row.setUpdatedAt(now); row.setLeaseUntil(lease); return row;
    }
}
