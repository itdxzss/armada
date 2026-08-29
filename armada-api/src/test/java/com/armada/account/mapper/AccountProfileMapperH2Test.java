package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountProfile;
import com.armada.boot.config.MyBatisConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 账号画像各事实独立水位、首次注册事实与显式租户边界的 H2 Mapper 测试。 */
@SpringJUnitConfig(AccountProfileMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountProfileMapperH2Test {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private AccountProfileMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted_at BIGINT)
                """);
        execute("""
                CREATE TABLE account_profile (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  friend_count INT,
                  friend_count_synced_at BIGINT,
                  is_group_invite_allowed TINYINT,
                  group_invite_synced_at BIGINT,
                  rotation_status TINYINT,
                  rotation_updated_at BIGINT,
                  registered_at BIGINT,
                  registered_at_source TINYINT,
                  marketing_source TINYINT,
                  marketing_source_updated_at BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE (tenant_id, account_id))
                """);
        execute("INSERT INTO account VALUES (1,7,NULL),(2,8,NULL),(3,7,1000)");
    }

    @Test
    void eachAsynchronousFactRejectsOlderAndEqualWatermarksIndependently() {
        assertThat(mapper.upsertFriendCount(7, 1, 10, 200, 1_000)).isEqualTo(1);
        mapper.upsertFriendCount(7, 1, 5, 100, 1_100);
        mapper.upsertFriendCount(7, 1, 99, 200, 1_200);
        mapper.upsertGroupInviteAllowed(7, 1, true, 300, 1_300);
        mapper.upsertGroupInviteAllowed(7, 1, false, 299, 1_400);
        mapper.upsertRotationStatus(7, 1, 2, 400, 1_500);
        mapper.upsertRotationStatus(7, 1, 3, 399, 1_600);
        mapper.upsertMarketingSource(7, 1, 4, 500, 1_700);
        mapper.upsertMarketingSource(7, 1, 0, 499, 1_800);

        AccountProfile profile = mapper.selectByTenantAndAccountId(7, 1);
        assertThat(profile.getFriendCount()).isEqualTo(10);
        assertThat(profile.getFriendCountSyncedAt()).isEqualTo(200);
        assertThat(profile.getGroupInviteAllowed()).isTrue();
        assertThat(profile.getGroupInviteSyncedAt()).isEqualTo(300);
        assertThat(profile.getRotationStatus()).isEqualTo(2);
        assertThat(profile.getRotationUpdatedAt()).isEqualTo(400);
        assertThat(profile.getMarketingSource()).isEqualTo(4);
        assertThat(profile.getMarketingSourceUpdatedAt()).isEqualTo(500);
        assertThat(profile.getUpdatedAt()).isEqualTo(1_800);
    }

    @Test
    void registrationIsFirstKnownFactAndForeignOrDeletedAccountsCannotCreateProfiles() {
        assertThat(mapper.initializeRegistration(7, 1, 100, 2, 1_000)).isEqualTo(1);
        mapper.initializeRegistration(7, 1, 200, 1, 2_000);

        assertThat(mapper.selectByTenantAndAccountId(7, 1))
                .satisfies(profile -> {
                    assertThat(profile.getRegisteredAt()).isEqualTo(100);
                    assertThat(profile.getRegisteredAtSource()).isEqualTo(2);
                });
        assertThat(mapper.upsertFriendCount(7, 2, 10, 200, 1_000)).isZero();
        assertThat(mapper.upsertFriendCount(7, 3, 10, 200, 1_000)).isZero();
        assertThat(mapper.selectByTenantAndAccountId(8, 1)).isNull();
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
            source.setURL("jdbc:h2:mem:account_profile;MODE=MySQL;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/account/AccountProfileMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountProfileMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(AccountProfileMapper.class);
        }
    }
}
