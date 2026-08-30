package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号类型校验更新的凭据版本、租户和水位 H2 Mapper 测试。 */
@SpringJUnitConfig(AccountTypeVerificationMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountTypeVerificationMapperH2Test {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private AccountMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE account (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  ws_phone VARCHAR(32),
                  account_type TINYINT NOT NULL,
                  declared_account_type TINYINT NOT NULL,
                  account_type_verify_status TINYINT NOT NULL,
                  account_type_verify_source TINYINT,
                  account_type_verified_at BIGINT,
                  business_verification_level TINYINT,
                  business_verification_source TINYINT,
                  business_verification_verified_at BIGINT,
                  device_os TINYINT,
                  number_source TINYINT,
                  channel_name VARCHAR(191),
                  ownership TINYINT,
                  lease_until BIGINT,
                  account_group_id BIGINT,
                  protocol_id VARCHAR(64),
                  protocol_account_id VARCHAR(191),
                  protocol_address VARCHAR(255),
                  priority INT,
                  dispatched_at BIGINT,
                  remark VARCHAR(255),
                  created_at BIGINT,
                  updated_at BIGINT NOT NULL,
                  created_by BIGINT,
                  deleted_at BIGINT)
                """);
        execute("""
                CREATE TABLE account_credential (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT)
                """);
        execute("""
                INSERT INTO account (
                  id, tenant_id, account_type, declared_account_type,
                  account_type_verify_status, account_type_verify_source,
                  account_type_verified_at, protocol_account_id, updated_at, deleted_at
                ) VALUES (100,7,1,1,0,NULL,NULL,'acc_100',1000,NULL)
                """);
        execute("UPDATE account SET protocol_id='ANDROID' WHERE id=100");
        execute("INSERT INTO account_credential VALUES (200,7,100,1788000000000,NULL)");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void genericInsertBackfillsLegacyVerificationFields() throws SQLException {
        Account account = new Account();
        account.setWsPhone("8613800000000");
        account.setAccountType(2);
        account.setCreatedAt(1_788_000_000_000L);
        account.setUpdatedAt(1_788_000_000_000L);

        assertThat(mapper.insert(account)).isEqualTo(1);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT declared_account_type, account_type_verify_status
                     FROM account WHERE id=%d
                     """.formatted(account.getId()))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
            assertThat(result.getInt(2)).isEqualTo(4);
        }
    }

    @Test
    void updatesOnlyMatchingCredentialVersionAndRejectsOlderDetectionWatermark() throws SQLException {
        Account update = update(2, 2, 3, 1_788_100_000_000L);

        assertThat(mapper.updateTypeVerification(update, 1_788_000_000_000L)).isEqualTo(1);
        assertThat(current("account_type")).isEqualTo(2L);
        assertThat(current("account_type_verify_status")).isEqualTo(2L);
        assertThat(current("business_verification_level")).isEqualTo(1L);

        assertThat(mapper.updateTypeVerification(update, 1_788_000_000_000L)).isZero();
        Account stale = update(1, 1, 3, 1_788_099_999_999L);
        assertThat(mapper.updateTypeVerification(stale, 1_788_000_000_000L)).isZero();
        assertThat(mapper.updateTypeVerification(update, 1_788_000_000_001L)).isZero();
        assertThat(current("account_type")).isEqualTo(2L);

        Account inconclusive = update(1, 3, 3, 1_788_100_000_001L);
        inconclusive.setAccountType(null);
        assertThat(mapper.updateTypeVerification(inconclusive, 1_788_000_000_000L)).isEqualTo(1);
        assertThat(current("account_type")).isEqualTo(2L);
        assertThat(current("account_type_verify_status")).isEqualTo(3L);
    }

    private static Account update(int type, int status, int source, long detectedAt) {
        Account account = new Account();
        account.setTenantId(7L);
        account.setId(100L);
        account.setProtocolAccountId("acc_100");
        account.setProtocolId("ANDROID");
        account.setAccountType(type);
        account.setAccountTypeVerifyStatus(status);
        account.setAccountTypeVerifySource(source);
        account.setAccountTypeVerifiedAt(detectedAt);
        account.setBusinessVerificationLevel(1);
        account.setBusinessVerificationSource(source);
        account.setBusinessVerificationVerifiedAt(detectedAt);
        account.setUpdatedAt(detectedAt + 1);
        return account;
    }

    private long current(String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + column + " FROM account WHERE id=100")) {
            result.next();
            return result.getLong(1);
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
            source.setURL("jdbc:h2:mem:account_type_verification;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
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
