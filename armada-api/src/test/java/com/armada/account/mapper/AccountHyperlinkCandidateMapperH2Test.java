package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.boot.config.MyBatisConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 超链账号完整可支持筛选、固定条件与显式租户边界的 H2 Mapper 测试。 */
@SpringJUnitConfig(AccountHyperlinkCandidateMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountHyperlinkCandidateMapperH2Test {

    private static final long NOW = 2_000_000_000_000L;
    private static final long DAY = 86_400_000L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountMapper mapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() throws SQLException {
        executor = Executors.newFixedThreadPool(2);
        execute("DROP ALL OBJECTS");
        schema();
        fixture();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void appliesEveryFilterBackedByCurrentSchemaAndReturnsDerivedSnapshots() {
        AccountHyperlinkCandidateQuery query = new AccountHyperlinkCandidateQuery(
                List.of("BR"), List.of(), "SOUTH_AMERICA", List.of(10L), List.of(20L), "WEB",
                "ONLINE", 2, 2, "ANDROID_BUSINESS_COMPANION", "web5", "full_param",
                true, "5512", 30L, 4, 10, 10,
                null, null, BigDecimal.valueOf(4.9), BigDecimal.valueOf(5.1), 90, 90,
                NOW - 6 * DAY, NOW - 4 * DAY, List.of("WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, query, null, null, 10))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.accountId()).isEqualTo(1L);
                    assertThat(candidate.countryIso2()).isEqualTo("BR");
                    assertThat(candidate.deviceOs()).isEqualTo(1);
                    assertThat(candidate.protocolBackend()).isEqualTo("WEB");
                });
        assertThat(mapper.countHyperlinkCandidates(7L, query)).isEqualTo(1);
    }

    @Test
    void fixedValidityAndExplicitTenantPreventMutedBannedDeletedAndForeignRows() {
        AccountHyperlinkCandidateQuery empty = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("ANDROID", "WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, empty, null, null, 20))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(1L, 5L);
        assertThat(mapper.selectHyperlinkCandidates(8L, empty, null, null, 20))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(6L);
        assertThat(mapper.countHyperlinkCandidates(7L, empty)).isEqualTo(2);
        assertThat(mapper.countHyperlinkCandidates(8L, empty)).isEqualTo(1);

        assertThat(mapper.countHyperlinkProtocols(7L, List.of("ANDROID", "WEB")))
                .isEqualTo(2);
        assertThat(mapper.countHyperlinkProtocols(8L, List.of("WEB")))
                .isEqualTo(1);
        assertThat(mapper.selectHyperlinkProtocolIds(7L, List.of("ANDROID", "WEB")))
                .containsExactly("ANDROID", "WEB");
        assertThat(mapper.selectHyperlinkProtocolIds(8L, List.of("WEB")))
                .containsExactly("WEB");

        AccountHyperlinkCandidateQuery offline = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                "OFFLINE", null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("ANDROID", "WEB"), NOW);
        assertThat(mapper.selectHyperlinkCandidates(7L, offline, null, null, 20))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(5L);
        assertThat(mapper.countHyperlinkCandidates(7L, offline)).isEqualTo(1);

        AccountHyperlinkCandidateQuery androidOnly = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("ANDROID"), NOW);
        assertThat(mapper.selectHyperlinkCandidates(7L, androidOnly, null, null, 1))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(5L);
        assertThat(mapper.countHyperlinkCandidates(7L, androidOnly)).isEqualTo(1);
    }

    @Test
    void derivesPrimaryOrCompanionFromCredentialFormatBeforeProtocolIdFallback()
            throws SQLException {
        insertAccount(7, 7, "551240", 2, 1, 10, 20, "ANDROID", NOW - DAY, null);
        credential(7, 7, 3);
        state(7, 7, 2, 1, null);
        AccountHyperlinkCandidateQuery companion = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), "ANDROID",
                null, null, null, "ANDROID_BUSINESS_COMPANION", "web5", "full_param",
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, companion, null, null, 20))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(7L);
    }

    @Test
    void iosNativeFullFormatIsAndroidPrimaryAndMatchesFullParamFilter()
            throws SQLException {
        insertAccount(12, 7, "447700900123", 2, 2, 10, 20, "WEB", NOW - DAY, null);
        credential(12, 7, 4);
        state(12, 7, 2, 1, null);
        AccountHyperlinkCandidateQuery query = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                "ONLINE", null, 2, "IOS_BUSINESS_PRIMARY", "native6", "full_param",
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("ANDROID"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, query, null, null, 20))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.accountId()).isEqualTo(12L);
                    assertThat(candidate.protocolBackend()).isEqualTo("ANDROID");
                });
        assertThat(mapper.countHyperlinkCandidates(7L, query)).isEqualTo(1);
    }

    @Test
    void resolvesHyphenatedAndSlashSeparatedCountryPrefixesBeforeGenericSharedPrefix()
            throws SQLException {
        execute("INSERT INTO country VALUES (3,'AS','+1-684','OCEANIA',1,NULL)");
        execute("INSERT INTO country VALUES (4,'DO','+1-809/829/849','NORTH_AMERICA',1,NULL)");
        insertAccount(8, 7, "16845551234", 1, 1, 10, 20, "WEB", NOW - DAY, null);
        credential(8, 7, 3);
        state(8, 7, 2, 1, null);
        insertAccount(9, 7, "18295551234", 1, 1, 10, 20, "WEB", NOW - DAY, null);
        credential(9, 7, 3);
        state(9, 7, 2, 1, null);
        AccountHyperlinkCandidateQuery query = new AccountHyperlinkCandidateQuery(
                List.of("AS", "DO"), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, query, null, null, 20))
                .extracting(candidate -> candidate.countryIso2())
                .containsExactly("AS", "DO");
    }

    @Test
    void compositeCursorContinuesAfterAFullPageWithoutGrowingTheLimit() throws SQLException {
        insertAccount(7, 7, "551240", 2, 1, 10, 20, "WEB", NOW - DAY, null);
        credential(7, 7, 3);
        state(7, 7, 2, 1, null);
        execute("UPDATE account SET priority=1 WHERE id=5");
        AccountHyperlinkCandidateQuery query = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("ANDROID", "WEB"), NOW);

        List<AccountHyperlinkCandidateVO> firstPage =
                mapper.selectHyperlinkCandidates(7L, query, null, null, 2);
        assertThat(firstPage).extracting(candidate -> candidate.accountId())
                .containsExactly(5L, 1L);

        var last = firstPage.get(firstPage.size() - 1);
        assertThat(mapper.selectHyperlinkCandidates(
                7L, query, last.priority(), last.accountId(), 2))
                .extracting(candidate -> candidate.accountId())
                .containsExactly(7L);
    }

    @Test
    void profileRangesAreInclusiveUnknownDoesNotMatchAndCountUsesIdenticalSql() {
        AccountHyperlinkCandidateQuery query = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, 2, null, null, null, null,
                true, null, null, 4, 10, 10,
                null, null, null, null, 90, 90,
                null, null, List.of("ANDROID", "WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, query, null, null, 20))
                .extracting(AccountHyperlinkCandidateVO::accountId)
                .containsExactly(1L);
        assertThat(mapper.countHyperlinkCandidates(7L, query)).isEqualTo(1);

        AccountHyperlinkCandidateQuery outsideRange = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, 2, null, null, null, null,
                true, null, null, 4, 11, null,
                null, null, null, null, 91, null,
                null, null, List.of("ANDROID", "WEB"), NOW);
        assertThat(mapper.selectHyperlinkCandidates(7L, outsideRange, null, null, 20))
                .isEmpty();
        assertThat(mapper.countHyperlinkCandidates(7L, outsideRange)).isZero();
    }

    @Test
    void registerDayFilterRejectsUnknownAndFutureRegistrationFacts() throws SQLException {
        insertAccount(10, 7, "551241", 2, 1, 10, 20, "WEB", NOW - DAY, null);
        credential(10, 7, 3);
        state(10, 7, 2, 1, null);
        profile(10, 7, 10, true, 2, NOW + DAY, 4);
        AccountHyperlinkCandidateQuery maxOnly = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, 90,
                null, null, List.of("ANDROID", "WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, maxOnly, null, null, 20))
                .extracting(AccountHyperlinkCandidateVO::accountId)
                .containsExactly(1L);
        assertThat(mapper.countHyperlinkCandidates(7L, maxOnly)).isEqualTo(1);
    }

    @Test
    void falseInviteFilterIsARealConditionRatherThanAnUnsetValue() throws SQLException {
        insertAccount(11, 7, "551242", 2, 1, 10, 20, "WEB", NOW - DAY, null);
        credential(11, 7, 3);
        state(11, 7, 2, 1, null);
        profile(11, 7, 10, false, 2, NOW - 90 * DAY, 4);
        AccountHyperlinkCandidateQuery inviteDenied = new AccountHyperlinkCandidateQuery(
                List.of(), List.of(), null, List.of(), List.of(), null,
                null, null, null, null, null, null,
                false, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of("WEB"), NOW);

        assertThat(mapper.selectHyperlinkCandidates(7L, inviteDenied, null, null, 20))
                .extracting(AccountHyperlinkCandidateVO::accountId)
                .containsExactly(11L);
        assertThat(mapper.countHyperlinkCandidates(7L, inviteDenied)).isEqualTo(1);
    }

    @Test
    void hyperlinkDispatchAccountRowLockSerializesConcurrentTasks() throws Exception {
        Long foreignTenant = new TransactionTemplate(transactionManager)
                .execute(status -> mapper.lockActiveForHyperlinkDispatch(8L, 1L));
        assertThat(foreignTenant).isNull();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Long> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    Long accountId = mapper.lockActiveForHyperlinkDispatch(7L, 1L);
                    locked.countDown();
                    await(release);
                    return accountId;
                }));

        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch contenderCalling = new CountDownLatch(1);
        Future<Long> contender = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    contenderCalling.countDown();
                    return mapper.lockActiveForHyperlinkDispatch(7L, 1L);
                }));
        assertThat(contenderCalling.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> contender.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }

        assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo(1L);
        assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(1L);
    }

    private void fixture() throws SQLException {
        execute("INSERT INTO country VALUES (1,'BR','+55','SOUTH_AMERICA',1,NULL)");
        execute("INSERT INTO country VALUES (2,'US','+1','NORTH_AMERICA',1,NULL)");
        execute("INSERT INTO country_phone_prefix_mapping VALUES ('1','US')");
        insertAccount(1, 7, "551234", 2, 1, 10, 20, "WEB", NOW - 5 * DAY, null);
        credential(1, 7, 3);
        state(1, 7, 2, 1, null);
        profile(1, 7, 10, true, 2, NOW - 90 * DAY, 4);
        execute("INSERT INTO account_import_detail VALUES (100,7,30,1)");
        insertAccount(2, 7, "551235", 2, 1, 10, 20, "WEB", NOW - 5 * DAY, null);
        credential(2, 7, 3);
        state(2, 7, 3, 1, null);
        insertAccount(3, 7, "551236", 2, 1, 10, 20, "WEB", NOW - 5 * DAY, null);
        credential(3, 7, 3);
        state(3, 7, 2, 1, 1);
        insertAccount(4, 7, "551237", 2, 1, 10, 20, "WEB", NOW - 5 * DAY, 9L);
        credential(4, 7, 3);
        state(4, 7, 2, 1, null);
        insertAccount(5, 7, "551238", 1, 2, 11, 21, "ANDROID", NOW - DAY, null);
        credential(5, 7, 1);
        state(5, 7, 2, 2, null);
        insertAccount(6, 8, "551239", 2, 1, 10, 20, "WEB", NOW - 5 * DAY, null);
        credential(6, 8, 3);
        state(6, 8, 2, 1, null);
        profile(6, 8, 10, true, 2, NOW - 90 * DAY, 4);
    }

    private void schema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32),
                  account_type INT, device_os INT, account_group_id BIGINT,
                  promotion_channel_id BIGINT, protocol_id VARCHAR(32),
                  protocol_account_id VARCHAR(64), protocol_address VARCHAR(128),
                  priority INT, created_at BIGINT,
                  deleted_at BIGINT)
                """);
        execute("""
                CREATE TABLE account_state (
                  account_id BIGINT, tenant_id BIGINT, account_state INT,
                  login_state INT, mute_status INT, PRIMARY KEY (tenant_id, account_id))
                """);
        execute("""
                CREATE TABLE account_credential (
                  account_id BIGINT, tenant_id BIGINT, cred_format INT, deleted_at BIGINT,
                  PRIMARY KEY (tenant_id, account_id))
                """);
        execute("""
                CREATE TABLE account_import_detail (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT, batch_id BIGINT, account_id BIGINT)
                """);
        execute("""
                CREATE TABLE account_profile (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, account_id BIGINT,
                  friend_count INT, is_group_invite_allowed TINYINT, rotation_status TINYINT,
                  registered_at BIGINT, marketing_source TINYINT,
                  contact_named_num INT, contact_named_synced_at BIGINT,
                  UNIQUE (tenant_id, account_id))
                """);
        execute("""
                CREATE TABLE country (
                  id BIGINT PRIMARY KEY, iso2 VARCHAR(2), phone_prefix VARCHAR(16),
                  continent_code VARCHAR(24), is_enabled INT, deleted_at BIGINT)
                """);
        execute("""
                CREATE TABLE country_phone_prefix_mapping (
                  normalized_prefix VARCHAR(16) PRIMARY KEY, country_iso2 VARCHAR(2))
                """);
    }

    private void insertAccount(long id, long tenantId, String phone, int type, int device,
            long groupId, long channelId, String protocol, long createdAt, Long deletedAt)
            throws SQLException {
        execute(("INSERT INTO account VALUES (%d,%d,'%s',%d,%d,%d,%d,'%s','acc-%d',"
                + "'node-%s',0,%d,%s)")
                .formatted(id, tenantId, phone, type, device, groupId, channelId, protocol, id,
                        protocol.toLowerCase(), createdAt,
                        deletedAt == null ? "NULL" : deletedAt));
    }

    private void credential(long accountId, long tenantId, int format) throws SQLException {
        execute("INSERT INTO account_credential VALUES (%d,%d,%d,NULL)"
                .formatted(accountId, tenantId, format));
    }

    private void state(long accountId, long tenantId, int accountState, int loginState,
            Integer muteStatus) throws SQLException {
        execute("INSERT INTO account_state VALUES (%d,%d,%d,%d,%s)"
                .formatted(accountId, tenantId, accountState, loginState,
                        muteStatus == null ? "NULL" : muteStatus));
    }

    private void profile(long accountId, long tenantId, int friendCount, boolean inviteAllowed,
            int rotationStatus, long registeredAt, int marketingSource) throws SQLException {
        execute("INSERT INTO account_profile (tenant_id,account_id,friend_count,"
                + "is_group_invite_allowed,rotation_status,registered_at,marketing_source) "
                + "VALUES (%d,%d,%d,%d,%d,%d,%d)".formatted(
                        tenantId, accountId, friendCount, inviteAllowed ? 1 : 0,
                        rotationStatus, registeredAt, marketingSource));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待释放账号行锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待释放账号行锁被中断", exception);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_account_candidate;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(AccountMapper.class);
        }
    }
}
